package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImportDraft
import com.music.bitchord.data.spotify.SpotifyImportState
import com.music.bitchord.data.spotify.SpotifyMatch
import com.music.bitchord.data.spotify.SpotifyTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an import shows before it writes anything.
 *
 * The split between matched and missed is the whole reason a draft exists: it is
 * the difference between "here is your playlist" and "here is your playlist,
 * except for these five, which do not exist on Spotify's side or anywhere else
 * this app can play".
 */
class SpotifyImportDraftTest {

    private fun track(title: String) = SpotifyTrack(
        id = "id-$title", title = title, artist = "Tame Impala", album = null,
        durationSec = 216, isExplicit = false,
    )

    private fun song(title: String) =
        Song(videoId = "v-$title", title = title, artist = "Tame Impala", thumbnailUrl = null)

    private fun draft(vararg titles: Pair<String, Boolean>) = SpotifyImportDraft(
        title = "chill",
        artworkUrl = null,
        matches = titles.map { (title, hit) ->
            SpotifyMatch(track(title), if (hit) song(title) else null)
        },
        atTrackLimit = false,
    )

    @Test
    fun splitsMatchedFromMissed() {
        val draft = draft("A" to true, "B" to false, "C" to true, "D" to false)
        assertEquals(listOf("A", "C"), draft.matched.map { it.title })
        // A missed track is reported, never dropped: a list that quietly lost
        // them would misstate what was imported.
        assertEquals(listOf("B", "D"), draft.missed.map { it.spotify.title })
    }

    /** Order is the playlist's, so the preview reads as the playlist will. */
    @Test
    fun keepsPlaylistOrder() {
        val draft = draft("A" to true, "B" to false, "C" to true)
        assertEquals(listOf("A", "B", "C"), draft.matches.map { it.spotify.title })
    }

    @Test
    fun nothingMatchedLeavesBothSidesEmpty() {
        val draft = draft("A" to false)
        assertTrue(draft.matched.isEmpty())
        assertEquals(1, draft.missed.size)
    }

    /** The 100-track cap is carried so it can be said, not discovered later. */
    @Test
    fun carriesTheTrackLimitFlag() {
        assertTrue(draft("A" to true).atTrackLimit.not())
        assertTrue(draft("A" to true).copy(atTrackLimit = true).atTrackLimit)
    }

    @Test
    fun failureIsAReasonRatherThanAMessage() {
        // A message here would be English chosen in the data layer; the wording
        // belongs to whichever surface shows it.
        val state = SpotifyImportState.Failed(SpotifyImportState.Failure.Unreadable)
        assertEquals(SpotifyImportState.Failure.Unreadable, (state as SpotifyImportState.Failed).reason)
    }

    /**
     * Every reason a surface may have to word differently, named rather than
     * counted — a new one should not fail an unrelated test, but it should fail
     * this one until whoever adds it has decided how to say it.
     */
    @Test
    fun everyFailureHasADistinctReason() {
        val reasons = SpotifyImportState.Failure.entries
        assertEquals(
            setOf("BadLink", "Unreadable", "Unreachable", "NothingMatched", "WriteFailed"),
            reasons.map { it.name }.toSet(),
        )
    }

    /**
     * A short import is stated, not smoothed. `added` below `requested` is the
     * only signal the listener gets that tracks went missing on the way in.
     */
    @Test
    fun doneCarriesWhatWasActuallyWritten() {
        val done = SpotifyImportState.Done(playlistId = "PL1", added = 34, requested = 36)
        assertEquals(34, done.added)
        assertEquals(36, done.requested)
    }
}
