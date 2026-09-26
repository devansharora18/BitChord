package com.music.bitchord.data.spotify

/** What a Spotify link points at. Only the two kinds an import can act on. */
data class SpotifyRef(val id: String, val kind: Kind) {
    enum class Kind { PLAYLIST, ALBUM }
}

/**
 * Turns whatever the listener pasted into a [SpotifyRef].
 *
 * The input arrives by hand and by share sheet, so it is not one shape: a web
 * link off `open.spotify.com`, a `spotify:` URI off the Spotify app, or a bare
 * id. All of them end in the same base-62 id, and the id plus the kind are the
 * only parts that go on the wire, so this reduces each of them to that.
 *
 * Deliberately plain string handling rather than `android.net.Uri`: this is the
 * whole of the import's input handling, and a parser that can only be exercised
 * on a device is a parser that will not be. See `PlaylistLinkTest`.
 */
internal object SpotifyLink {

    private val HOSTS = setOf("open.spotify.com", "play.spotify.com", "spotify.com", "www.spotify.com")

    /**
     * Spotify ids are 22 base-62 characters. Checked rather than assumed, because
     * everything downstream spends a request to find out — a link of the wrong
     * shape is a typo, and this is where the app can say so for free.
     */
    private val ID = Regex("^[A-Za-z0-9]{22}$")

    fun parse(input: String): SpotifyRef? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) return parseUri(text)

        val afterScheme = text.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore(':').lowercase()
        if (host !in HOSTS) return null

        val segments = afterScheme
            .substringAfter('/', missingDelimiterValue = "")
            .substringBefore('?')
            .substringBefore('#')
            .split('/')
        // The kind is searched for rather than read off a fixed position: Spotify
        // localises the path (`/intl-de/album/ID`) and nests it under `/embed/`,
        // so its index is not a constant. Anything after the id — a track slug,
        // on some links — is not this parser's business.
        val kind = SpotifyRef.Kind.entries.firstOrNull { kind ->
            segments.any { it.equals(kind.name, ignoreCase = true) }
        } ?: return null
        val index = segments.indexOfFirst { it.equals(kind.name, ignoreCase = true) }
        val id = segments.getOrNull(index + 1)?.takeIf { ID.matches(it) } ?: return null
        return SpotifyRef(id, kind)
    }

    private fun parseUri(text: String): SpotifyRef? {
        val parts = text.split(':')
        if (parts.size < 3 || parts[0] != "spotify") return null
        val kind = when (parts[1].lowercase()) {
            "playlist" -> SpotifyRef.Kind.PLAYLIST
            "album" -> SpotifyRef.Kind.ALBUM
            else -> return null
        }
        val id = parts[2].substringBefore('?')
        return if (ID.matches(id)) SpotifyRef(id, kind) else null
    }
}
