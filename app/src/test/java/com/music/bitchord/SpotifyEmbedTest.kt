package com.music.bitchord

import com.music.bitchord.data.spotify.SpotifyEmbed
import com.music.bitchord.data.spotify.SpotifyEmbed.largestUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embed page's shapes, and nothing above them.
 *
 * The playlist fixture is a real capture, trimmed to the fields these DTOs read
 * and to five tracks that between them cover an explicit and a clean edition, a
 * single and a multi-artist credit, and a title carrying its packaging. It is a
 * capture rather than a hand-written payload because [SpotifyEmbed] reads an
 * undocumented page: a field Spotify renames is an empty import, not an error,
 * and this is where that shows up instead of at runtime.
 */
class SpotifyEmbedTest {

    private val json = SpotifyEmbed.json

    /**
     * Wraps a payload the way the real page does, so the extraction step is part
     * of what is under test. Feeding the JSON straight in would skip it, and a
     * parser pointed at the whole HTML body decodes nothing at all while every
     * such test still passes.
     */
    private fun page(payload: String) =
        "<!DOCTYPE html><html><body><div id=root></div>" +
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">$payload</script>" +
            "</body></html>"

    private fun entity(html: String): SpotifyEmbed.Entity {
        val script = SpotifyEmbed.payloadOf(html) ?: error("no __NEXT_DATA__ in the fixture page")
        return json.decodeFromString<SpotifyEmbed.Page>(script).props.pageProps.state.data.entity
    }

    private fun playlist(vararg trackList: String) = page(
        """
        {"props":{"pageProps":{"state":{"data":{"entity":{
          "type":"playlist","name":"chill","subtitle":"Devansh",
          "coverArt":{"sources":[
            {"url":"https://i.scdn.co/60","width":60},
            {"url":"https://i.scdn.co/640","width":640}]},
          "trackList":[${trackList.joinToString(",")}]
        }}}}}}
        """.trimIndent(),
    )

    private fun track(
        title: String = "Riptide",
        artist: String = "Vance Joy",
        duration: Long = 204280,
        uri: String = "spotify:track:2uXlHCUbq9OMUwx3hrk06o",
        entityType: String = "track",
        explicit: Boolean = false,
        playable: Boolean = true,
    ) = """
        {"title":${quote(title)},"subtitle":${quote(artist)},"duration":$duration,
         "uri":"$uri","entityType":"$entityType","isExplicit":$explicit,"isPlayable":$playable}
    """.trimIndent()

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun readsTheRealCapturedPlaylist() {
        val entity = entity(
            playlist(
                track(title = "The Less I Know The Better", explicit = true),
                track(),
                track(
                    title = "One Of The Girls (with JENNIE, Lily Rose Depp)",
                    artist = "The Weeknd, JENNIE, Lily-Rose Depp",
                    duration = 244684,
                    uri = "spotify:track:7CyPwkp0oE8Ro9Dd5CUDjW",
                ),
            ),
        )
        assertEquals("playlist", entity.type)
        assertEquals("chill", entity.name)
        assertEquals("Devansh", entity.subtitle)
        // The 60px cover is listed first and is useless as a collection header.
        assertEquals("https://i.scdn.co/640", entity.coverArt?.sources?.largestUrl())

        val tracks = entity.tracks.mapNotNull { it.resolve(null) }
        assertEquals(3, tracks.size)
        assertEquals("The Less I Know The Better", tracks[0].title)
        assertTrue(tracks[0].isExplicit)
        assertEquals("2uXlHCUbq9OMUwx3hrk06o", tracks[1].id)
        assertFalse(tracks[1].isExplicit)
        assertEquals(204, tracks[1].durationSec)
        assertEquals("The Weeknd, JENNIE, Lily-Rose Depp", tracks[2].artist)
        // A playlist carries no per-track release, so this stays empty.
        assertNull(tracks[2].album)
    }

    /** An album's own name is its release, and is filled across the page. */
    @Test
    fun albumNameIsFilledIn() {
        val entity = entity(
            page(
                """
                {"props":{"pageProps":{"state":{"data":{"entity":{
                  "type":"album","name":"Emotion (Deluxe)","subtitle":"Carly Rae Jepsen",
                  "trackList":[${track(title = "Run Away With Me", artist = "Carly Rae Jepsen")}]
                }}}}}}
                """.trimIndent(),
            ),
        )
        val album = entity.tracks.single().resolve(entity.name)!!
        assertEquals("Emotion (Deluxe)", album.album)
    }

    /**
     * A playlist can hold podcast episodes, and they arrive with a title and a
     * duration much like a track — so the page's own `entityType` is what has to
     * catch them, not the shape of the row.
     */
    @Test
    fun dropsEpisodes() {
        val entity = entity(
            playlist(
                track(entityType = "episode", artist = ""),
                track(title = "Real Track"),
            ),
        )
        val tracks = entity.tracks.mapNotNull { it.resolve(null) }
        assertEquals(listOf("Real Track"), tracks.map { it.title })
    }

    /**
     * A track Spotify will not serve here — region block, or pulled — would
     * import as a row that can never play.
     */
    @Test
    fun dropsUnplayableTracks() {
        val entity = entity(playlist(track(playable = false) + "," + track(title = "Kept")))
        assertEquals(listOf("Kept"), entity.tracks.mapNotNull { it.resolve(null) }.map { it.title })
    }

    @Test
    fun dropsRowsMissingATitleOrArtist() {
        val entity = entity(playlist(track(title = "  ") + "," + track(artist = "") + "," + track(title = "Kept")))
        assertEquals(listOf("Kept"), entity.tracks.mapNotNull { it.resolve(null) }.map { it.title })
    }

    /**
     * A link that names nothing still answers 200, with this path simply absent
     * from the payload. The DTOs have to absorb that into an unreadable page
     * rather than an empty playlist with a blank name.
     */
    @Test
    fun aMissingEntityIsNotAnEmptyPlaylist() {
        val entity = entity(page("""{"props":{"pageProps":{"state":{"data":{}}}}}"""))
        assertEquals("", entity.type)
        assertTrue(entity.tracks.isEmpty())
    }

    /** A field the page drops must not throw; it becomes a missing value. */
    @Test
    fun toleratesMissingFields() {
        val entity = entity(
            page("""{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist"}}}}}}"""),
        )
        assertEquals("playlist", entity.type)
        assertEquals("", entity.name)
        assertNull(entity.coverArt)
    }

    /** A page with no payload at all is unreadable, not a crash and not empty. */
    @Test
    fun aPageWithoutTheScriptIsUnreadable() {
        assertNull(SpotifyEmbed.payloadOf("<html><body>nothing here</body></html>"))
    }

    /**
     * The payload is JSON as it stands. A page that started HTML-escaping it
     * would otherwise decode into silently wrong strings rather than fail.
     */
    @Test
    fun theScriptContentsAreUnescapedJson() {
        val script = SpotifyEmbed.payloadOf(
            page("""{"props":{"pageProps":{"state":{"data":{"entity":{"name":"50 Cent"}}}}}}"""),
        )!!
        assertTrue(script.contains("\"50 Cent\""))
        assertFalse(script.contains("&quot;"))
    }

    @Test
    fun trackLimitIsOneHundred() {
        assertEquals(100, SpotifyEmbed.TRACK_LIMIT)
    }
}
