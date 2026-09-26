package com.music.bitchord

import com.music.bitchord.data.spotify.SpotifyPlaylistService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shapes, and nothing above them: [SpotifyPlaylistService.fetch] needs
 * a bearer and a device, but the decode is where a field rename turns into a
 * silently empty import rather than an error.
 */
class SpotifyPlaylistServiceTest {

    private val service = SpotifyPlaylistService

    private fun page(body: String) = service.json.decodeFromString<SpotifyPlaylistService.RawPage>(body)

    /** A playlist wraps each track; an album's tracks *are* the entries. */
    @Test
    fun playlistPage() {
        val decoded = page(
            """
            {
              "items": [
                { "track": {
                    "id": "0eGsygTp906u18L0Oimnem",
                    "name": "Sunset Lover",
                    "duration_ms": 233453,
                    "explicit": false,
                    "artists": [{ "name": "Petit Biscuit" }, { "name": "Alt-J" }],
                    "album": { "name": "Presence", "images": [
                      { "url": "https://i.scdn.co/image/small", "width": 64, "height": 64 },
                      { "url": "https://i.scdn.co/image/big", "width": 640, "height": 640 }
                    ] }
                } }
              ],
              "next": "https://api.spotify.com/v1/playlists/37i9dQZF1DXcBWIGoYBM5M/tracks?offset=50&limit=50",
              "total": 120
            }
            """.trimIndent(),
        )

        val track = tracksOf(decoded).single()
        assertEquals("Sunset Lover", track.title)
        assertEquals("Petit Biscuit, Alt-J", track.artist)
        assertEquals("Presence", track.album)
        assertEquals(233, track.durationSec)
        assertTrue(!track.isExplicit)
        // Largest cover, not the 64px one Spotify lists first.
        assertEquals("https://i.scdn.co/image/big", track.artworkUrl)
        assertEquals(120, decoded.total)
        assertEquals(
            "https://api.spotify.com/v1/playlists/37i9dQZF1DXcBWIGoYBM5M/tracks?offset=50&limit=50",
            decoded.next,
        )
    }

    /**
     * An album's tracks carry no album of their own, so the name and cover the
     * album endpoint was fetched for are filled in across the page.
     */
    @Test
    fun albumPageInheritsTheAlbumsOwnName() {
        val decoded = page(
            """
            {
              "items": [
                { "id": "1abc", "name": "Nude", "duration_ms": 240000, "artists": [{ "name": "Radiohead" }] }
              ],
              "total": 1
            }
            """.trimIndent(),
        )
        val album = SpotifyPlaylistService.RawCollection(
            name = "In Rainbows",
            images = listOf(SpotifyPlaylistService.RawImage(url = "https://i.scdn.co/album", width = 640)),
        )
        val track = decoded.items.single().asTrack()!!.resolve(album)!!
        assertEquals("In Rainbows", track.album)
        assertEquals("https://i.scdn.co/album", track.artworkUrl)
    }

    /**
     * A playlist can hold podcast episodes, which arrive with a name and a
     * duration much like a track and no artist at all.
     */
    @Test
    fun dropsEpisodesAndRemovedEntries() {
        val decoded = page(
            """
            {
              "items": [
                { "track": null },
                { "id": "ep1", "name": "Episode One", "duration_ms": 3000000, "artists": [] },
                { "track": { "id": "ok1", "name": "Real Track", "duration_ms": 100000,
                             "artists": [{ "name": "Someone" }] } }
              ],
              "total": 3
            }
            """.trimIndent(),
        )
        val tracks = tracksOf(decoded)
        assertEquals(listOf("Real Track"), tracks.map { it.title })
    }

    /** An explicit flag that arrives absent must not be read as a clean edition. */
    @Test
    fun explicitDefaultsToFalse() {
        val decoded = page("""{"items":[{"id":"a","name":"T","artists":[{"name":"X"}]}]}""")
        val track = decoded.items.single().asTrack()!!.resolve(null)!!
        assertTrue(!track.isExplicit)
        // Absent duration stays absent rather than becoming zero.
        assertNull(track.durationSec)
    }

    private fun tracksOf(page: SpotifyPlaylistService.RawPage) =
        page.items.mapNotNull { it.asTrack() }.mapNotNull { it.resolve(null) }
}
