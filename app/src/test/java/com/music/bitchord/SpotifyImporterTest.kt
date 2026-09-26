package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyMatch
import com.music.bitchord.data.spotify.SpotifyTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape [SpotifyImporter.match] promises its caller, with the catalogue stubbed out.
 *
 * Only the bookkeeping is under test — what the searches actually return is
 * [com.music.bitchord.data.sources.TrackMatcher]'s business and is tested there.
 * What matters here is that an import cannot quietly reorder a playlist, cannot
 * report progress it did not do, and cannot swallow a track.
 */
class SpotifyImporterTest {

    private fun track(title: String) = SpotifyTrack(
        id = "id-$title",
        title = title,
        artist = "Tame Impala",
        album = null,
        durationSec = 216,
        isExplicit = false,
    )

    private fun song(title: String) = Song(
        videoId = "v-$title",
        title = title,
        artist = "Tame Impala",
        thumbnailUrl = null,
    )

    /** No catalogue behind these, so every track comes back unmatched. */
    @Test
    fun unmatchedTracksAreStillReported() = runTest {
        val tracks = listOf(track("A"), track("B"), track("C"))
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = SpotifyImporter.match(tracks) { done, total -> progress += done to total }

        assertEquals(3, result.size)
        assertTrue(result.all { it.song == null })
        assertEquals(listOf("A", "B", "C"), result.map { it.spotify.title })
    }

    /**
     * Results must come back in playlist order however the searches interleaved.
     * An import that reshuffles a playlist is worse than one that fails.
     */
    @Test
    fun resultsKeepPlaylistOrder() = runTest {
        val tracks = (1..25).map { track("T$it") }
        val seen = mutableListOf<Pair<Int, Int>>()

        val result = SpotifyImporter.match(tracks) { done, total -> seen += done to total }

        assertEquals(tracks.map { it.id }, result.map { it.spotify.id })
        // Progress only ever counts up, and finishes on the total.
        assertEquals(seen.map { it.first }.sorted(), seen.map { it.first })
        assertEquals(seen.last(), 25 to 25)
        assertTrue(seen.all { it.second == 25 })
    }

    @Test
    fun anEmptyListStillReportsCompletion() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        val result = SpotifyImporter.match(emptyList()) { done, total -> seen += done to total }
        assertTrue(result.isEmpty())
        assertEquals(listOf(0 to 0), seen)
    }

    @Test
    fun aMatchCarriesBothSides() {
        val t = track("The Less I Know The Better")
        val match = SpotifyMatch(t, song("The Less I Know The Better"))
        assertEquals(t, match.spotify)
        assertEquals("v-The Less I Know The Better", match.song?.videoId)
    }
}
