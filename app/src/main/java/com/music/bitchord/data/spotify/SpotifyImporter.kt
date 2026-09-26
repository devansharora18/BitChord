package com.music.bitchord.data.spotify

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One imported track and where it landed. [song] is null when nothing in the
 * catalogue was confidently the same recording, which is a real outcome and not
 * an error — see [match].
 */
data class SpotifyMatch(val spotify: SpotifyTrack, val song: Song?)

/**
 * Turns a Spotify track list into YouTube Music rows.
 *
 * Spotify serves no audio, so this is where an import actually resolves: each
 * track is asked for by name and the answer is checked for being the *same
 * recording* before it is accepted. That check is [TrackMatcher]'s, and it is
 * already the app's answer to this question everywhere else — a module, JioSaavn
 * or an addon asking for a track all go through it — so an import inherits the
 * app's existing judgement rather than inventing a second one.
 *
 * ### Why unmatched is a normal result
 *
 * Spotify and YouTube Music do not hold the same catalogue. Region-only releases,
 * catalogue removals, podcast-adjacent entries and tracks that simply were never
 * uploaded all land here, and a list that quietly dropped them would be a lie
 * about what was imported. So every track comes back either way, and the caller
 * decides what to do about the ones with no [Song].
 */
internal object SpotifyImporter {

    private const val TAG = "SpotifyImport"

    /**
     * Searches in flight at once. The same bound the explore shelves use for
     * artwork, and for the same reason: past a handful, requests queue behind
     * each other and the whole import slows down without finishing any sooner.
     */
    private const val MAX_PARALLEL = 4

    /**
     * Matches every track, reporting progress as answers land rather than at the
     * end — an import of a hundred tracks is a visible wait, and a bar that sits
     * at zero and then jumps is the one version of that wait people read as a
     * hang.
     *
     * Results come back in the order the tracks were given, whatever order the
     * searches actually finished in, so the list is a playlist and not a log.
     */
    suspend fun match(
        tracks: List<SpotifyTrack>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SpotifyMatch> {
        val answers = arrayOfNulls<SpotifyMatch>(tracks.size)
        val done = AtomicInteger()
        val limiter = Semaphore(MAX_PARALLEL)
        coroutineScope {
            tracks.forEachIndexed { index, track ->
                launch {
                    // The permit covers the searches and nothing else: the counter
                    // is bookkeeping, and holding a permit to bump it would
                    // serialise the reporting behind the work it reports on.
                    answers[index] = SpotifyMatch(track, limiter.withPermit { matchOne(track) })
                    onProgress(done.incrementAndGet(), tracks.size)
                }
            }
        }
        // An empty list has no launches to report through, and completion is part
        // of what this promises its caller.
        onProgress(tracks.size, tracks.size)
        val misses = answers.count { it?.song == null }
        if (misses > 0) TrackLog.d(TAG, "matched ${tracks.size - misses}/${tracks.size} tracks")
        return answers.filterNotNull()
    }

    /**
     * The one track, or null.
     *
     * [TrackMatcher.queries] hands back a second, looser query for catalogues
     * that file a track under its composer or its film rather than its singer.
     * YouTube Music mostly wants the first, so the second is only spent when the
     * first found nothing — which on a mixed playlist is a small number of
     * tracks and on a clean one is none.
     */
    private suspend fun matchOne(track: SpotifyTrack): Song? {
        val target = TrackMatcher.Target(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationSec,
            // Null for a playlist, and the matcher treats the album as a
            // tie-break rather than a requirement, so its absence costs ranking
            // rather than the match.
            album = track.album,
            isExplicit = track.isExplicit,
        )
        for (query in TrackMatcher.queries(target)) {
            val candidates = YtMusicRepository.search(query, SearchFilter.SONGS)
                .getOrDefault(emptyList())
                .filterIsInstance<SearchResult.Track>()
                .map { it.song }
            TrackMatcher.best(candidates, target)?.let { return it }
        }
        return null
    }
}
