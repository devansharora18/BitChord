package com.music.bitchord

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.data.spotify.SpotifyEmbed
import com.music.bitchord.data.spotify.SpotifyFetch
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The read-and-match half of the import, on a real device.
 *
 * Everything here is exercised against the live Spotify service, which is the
 * point: the embed page is undocumented, so the only thing that establishes it
 * still answers is asking it. A JVM test with a fixture cannot do that — it can
 * only confirm the parser agrees with itself.
 *
 * The write half is deliberately absent. Creating a playlist needs a signed-in
 * account, and this must never touch a real listener's library.
 */
@RunWith(AndroidJUnit4::class)
class SpotifyImportDeviceTest {

    private val link = "https://open.spotify.com/playlist/0rhj3YhzLUz8zqg1wQX7nf"

    @Test
    fun readsAndMatchesALivePlaylist() = runBlocking {
        val ref = SpotifyLink.parse(link)
        assertNotNull("the link should parse", ref)

        val read = SpotifyEmbed.fetch(ref!!)
        assertTrue(
            "expected a playlist, got $read",
            read is SpotifyFetch.Loaded,
        )
        val collection = (read as SpotifyFetch.Loaded).collection
        println("READ '${collection.title}' by ${collection.owner}: ${collection.tracks.size} tracks, atLimit=${collection.atTrackLimit}")
        println("ARTWORK ${collection.artworkUrl}")
        assertTrue("a playlist with no tracks is not an import", collection.tracks.isNotEmpty())
        assertNotNull("every track should carry its Spotify id", collection.tracks.first().id)

        val progress = mutableListOf<Int>()
        val matches = SpotifyImporter.match(collection.tracks) { done, _ -> progress += done }
        val hit = matches.count { it.song != null }
        println("MATCHED $hit/${matches.size}")
        matches.filter { it.song == null }.forEach {
            println("  MISSED ${it.spotify.title} | ${it.spotify.artist}")
        }

        // The point of the run: the device can reach the page and the catalogue.
        assertTrue("nothing matched, which means the flow is broken", hit > 0)
        // Progress has to actually move, or the bar in the sheet is a decoration.
        assertTrue("progress should reach the total", progress.max() == collection.tracks.size)
        // Order is the playlist's, whatever order the searches finished in.
        assertEquals(
            collection.tracks.map { it.id },
            matches.map { it.spotify.id },
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** An album fills its own name in as the release, which a playlist cannot. */
    @Test
    fun readsALiveAlbum() = runBlocking {
        val read = SpotifyEmbed.fetch(SpotifyLink.parse("1DFixLWuPkv3KT3TnV35m3")!!)
        assertTrue("expected an album, got $read", read is SpotifyFetch.Loaded)
        val album = (read as SpotifyFetch.Loaded).collection
        println("ALBUM '${album.title}' by ${album.owner}: ${album.tracks.size} tracks")
        assertTrue(album.tracks.isNotEmpty())
        assertEquals(album.title, album.tracks.first().album)
    }

    /** A link that names nothing is a message, not an empty playlist. */
    @Test
    fun unreadableLinkIsReportedNotEmptied() = runBlocking {
        val read = SpotifyEmbed.fetch(SpotifyLink.parse("aaaaaaaaaaaaaaaaaaaaaa")!!)
        assertEquals(SpotifyFetch.Unreadable, read)
    }
}
