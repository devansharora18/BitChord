package com.music.bitchord

import com.music.bitchord.data.spotify.SpotifyLink
import com.music.bitchord.data.spotify.SpotifyRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistLinkTest {

    private val playlist = "37i9dQZF1DXcBWIGoYBM5M"
    private val album = "1DFixLWuPkv3KT3TnV35m3"

    @Test
    fun openSpotifyWebUrl() {
        val ref = SpotifyLink.parse("https://open.spotify.com/playlist/$playlist?si=8a1f0c2e3b4d5e6f")
        assertEquals(SpotifyRef(playlist, SpotifyRef.Kind.PLAYLIST), ref)
    }

    @Test
    fun albumUrl() {
        assertEquals(
            SpotifyRef(album, SpotifyRef.Kind.ALBUM),
            SpotifyLink.parse("https://open.spotify.com/album/$album"),
        )
    }

    /**
     * The Spotify app's share sheet hands over a `spotify:` URI rather than a web
     * link, and the two arrive from the same paste box.
     */
    @Test
    fun spotifyUri() {
        assertEquals(
            SpotifyRef(playlist, SpotifyRef.Kind.PLAYLIST),
            SpotifyLink.parse("spotify:playlist:$playlist"),
        )
    }

    /** Spotify localises the path prefix, so `/intl-de/album/ID` is a normal album link. */
    @Test
    fun localisedPathPrefix() {
        assertEquals(
            SpotifyRef(album, SpotifyRef.Kind.ALBUM),
            SpotifyLink.parse("https://open.spotify.com/intl-de/album/$album"),
        )
    }

    /** Regional hosts, the `www` prefix and a trailing slug all appear in the wild. */
    @Test
    fun hostVariants() {
        assertEquals(
            SpotifyRef(playlist, SpotifyRef.Kind.PLAYLIST),
            SpotifyLink.parse("http://play.spotify.com/playlist/$playlist/some-track-name"),
        )
        assertEquals(
            SpotifyRef(playlist, SpotifyRef.Kind.PLAYLIST),
            SpotifyLink.parse("https://www.spotify.com/playlist/$playlist"),
        )
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(
            SpotifyRef(playlist, SpotifyRef.Kind.PLAYLIST),
            SpotifyLink.parse("  https://open.spotify.com/playlist/$playlist\n"),
        )
    }

    /**
     * A real listener-supplied link, of the shape the Spotify app and the web
     * player actually hand over — `?si=` share token included, since that is what
     * comes in on a share and it must not end up in the request path.
     */
    @Test
    fun realSharedLink() {
        assertEquals(
            SpotifyRef("0rhj3YhzLUz8zqg1wQX7nf", SpotifyRef.Kind.PLAYLIST),
            SpotifyLink.parse("https://open.spotify.com/playlist/0rhj3YhzLUz8zqg1wQX7nf?si=f1a2b3c4d5e6"),
        )
    }

    @Test
    fun rejectsNonSpotifyLinks() {
        assertNull(SpotifyLink.parse("https://music.youtube.com/playlist/OLAK5uy_abc"))
        assertNull(SpotifyLink.parse("https://open.spotify.com/track/$playlist"))
        assertNull(SpotifyLink.parse("https://open.spotify.com/artist/$playlist"))
        assertNull(SpotifyLink.parse(""))
    }

    /**
     * A bare id says nothing about what it names, so it is carried through as
     * [SpotifyRef.Kind.UNKNOWN] for the page itself to settle — rather than
     * guessed at here, where a wrong guess reads as "no such playlist".
     */
    @Test
    fun bareIdIsUnknownRatherThanGuessed() {
        assertEquals(
            SpotifyRef(playlist, SpotifyRef.Kind.UNKNOWN),
            SpotifyLink.parse(playlist),
        )
        assertEquals(
            SpotifyRef(playlist, SpotifyRef.Kind.UNKNOWN),
            SpotifyLink.parse("  $playlist  "),
        )
    }

    /** A base-62 check, not just a non-empty one: a typo'd id is a 404, not a track list. */
    @Test
    fun rejectsIdsOfTheWrongShape() {
        assertNull(SpotifyLink.parse("https://open.spotify.com/playlist/not a valid id"))
        assertNull(SpotifyLink.parse("https://open.spotify.com/playlist/short"))
        assertNull(SpotifyLink.parse("https://open.spotify.com/playlist/"))
    }
}
