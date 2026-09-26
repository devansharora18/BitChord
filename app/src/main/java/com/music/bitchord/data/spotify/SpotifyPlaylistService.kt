package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One track as Spotify describes it — identity and metadata, never audio. */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val artworkUrl: String?,
    val isExplicit: Boolean,
)

/** A playlist or album, and as much of its track list as [MAX_TRACKS] allows. */
data class SpotifyCollection(
    val title: String,
    val artworkUrl: String?,
    /** What Spotify says the collection holds, which can exceed `tracks.size`. */
    val total: Int,
    val tracks: List<SpotifyTrack>,
)

/**
 * Why a fetch did or didn't produce a collection.
 *
 * [Unreadable] deliberately merges "no such playlist" with "this playlist is
 * private" and with "the session has expired": all three answer with a status
 * Spotify gives for a link the listener cannot read, and in each case the thing
 * to do about it is the same — check the link, or re-paste the cookie. Only
 * [NotConnected] is worth separating out, because it is the one with a fix
 * somewhere else in the app.
 */
sealed interface SpotifyFetch {
    data class Loaded(val collection: SpotifyCollection) : SpotifyFetch
    data object NotConnected : SpotifyFetch
    data object Unreadable : SpotifyFetch
    data object Failed : SpotifyFetch
}

/**
 * Reads a playlist or album off Spotify's public catalogue API.
 *
 * The bearer is the listener's own, minted from the `sp_dc` cookie they paste in
 * Settings for the Canvas feature — see [SpotifyToken]. There is no other
 * credential in this app and no public API for a track's audio, so an import
 * brings across a *list of tracks* and nothing else: each one is then matched
 * against a catalogue that will serve it, and playback resolves as it does
 * everywhere else. That is the whole reason this is a list and not a download.
 *
 * Playlists and albums differ in one way that matters: a playlist's tracks each
 * carry their own album, an album's tracks do not, so the album's own name and
 * cover are filled in across the page. Everything else — the paging, the
 * headers, the shapes below — is the same call.
 */
internal object SpotifyPlaylistService {

    private const val TAG = "SpotifyImport"
    private const val API = "https://api.spotify.com/v1"

    /** Spotify's own maximum for these endpoints. */
    private const val PAGE_LIMIT = 50

    /**
     * Enough for any playlist anyone curates by hand, and a ceiling rather than a
     * target: every track here becomes its own catalogue search, so a 5,000-track
     * import is not a slow import but an abandoned one. Truncation is reported by
     * [SpotifyCollection.total] so the UI can say what it left out.
     */
    private const val MAX_TRACKS = 500

    // Not private: the two item shapes below are the part of this that can break
    // silently — a mis-declared field is an empty import, not a crash — and they
    // are the one piece here reachable without a device. See SpotifyPlaylistServiceTest.
    internal val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    suspend fun fetch(ref: SpotifyRef): SpotifyFetch = withContext(Dispatchers.IO) {
        // Checked before the harvest so an unset cookie reads as "not connected"
        // rather than as a failed mint — they need different words, and the
        // harvest is slow enough that firing it to find out is its own problem.
        if (AppSettings.spotifySpdcToken.value.isBlank()) {
            return@withContext SpotifyFetch.NotConnected
        }
        val token = SpotifyToken.accessToken()
        if (token == null) {
            TrackLog.w(TAG, "no access token; cookie unset or the mint failed")
            return@withContext SpotifyFetch.NotConnected
        }
        val headers = SpotifyToken.authHeaders(token)

        val (code, body) = Http.getWithStatus("$API/${ref.path(ref.kind)}/${ref.id}", headers)
        if (body == null) {
            TrackLog.w(TAG, "couldn't read ${ref.id}, http $code")
            // 401 and 403 are a dead session and 404 a link the listener can't see;
            // all three are answered the same way above, and -1 is a transport
            // failure, which is a different thing to tell someone about.
            return@withContext if (code in 401..404) SpotifyFetch.Unreadable else SpotifyFetch.Failed
        }
        val details = runCatching { json.decodeFromString<RawCollection>(body) }.getOrNull()
            ?: return@withContext SpotifyFetch.Failed
        val album = when (ref.kind) {
            SpotifyRef.Kind.ALBUM -> RawCollection(name = details.name, images = details.images)
            SpotifyRef.Kind.PLAYLIST -> null
        }
        val tracks = collect(ref, headers, album) ?: return@withContext SpotifyFetch.Unreadable

        SpotifyFetch.Loaded(
            SpotifyCollection(
                title = details.name.ifBlank { ref.id },
                artworkUrl = details.images.largestUrl(),
                total = tracks.second,
                tracks = tracks.first,
            ),
        )
    }

    /**
     * Walks the paged track list, returning the tracks read and the collection's
     * own count. Null means the first page itself failed; a later page that fails
     * ends the walk, because a partial list is still an import and the shortfall
     * shows up as fewer rows.
     */
    private fun collect(
        ref: SpotifyRef,
        headers: Map<String, String>,
        album: RawCollection?,
    ): Pair<List<SpotifyTrack>, Int>? {
        val tracks = mutableListOf<SpotifyTrack>()
        var url: String? = "$API/${ref.path(ref.kind)}/${ref.id}/tracks" +
            "?limit=$PAGE_LIMIT&offset=0&additional_types=track"
        var total = 0
        while (url != null && tracks.size < MAX_TRACKS) {
            val (code, body) = Http.getWithStatus(url, headers)
            if (body == null) {
                // The first page is the request that says whether the collection is
                // readable at all; later ones only shorten it.
                if (tracks.isEmpty()) {
                    TrackLog.w(TAG, "${ref.id} tracks page failed, http $code")
                    return null
                }
                TrackLog.w(TAG, "${ref.id} paging stopped at ${tracks.size} tracks, http $code")
                break
            }
            val page = runCatching { json.decodeFromString<RawPage>(body) }.getOrNull()
            if (page == null) {
                if (tracks.isEmpty()) return null
                break
            }
            total = page.total
            tracks += page.items.mapNotNull { it.asTrack() }.mapNotNull { it.resolve(album) }
            url = page.next
                // A paging URL is followed verbatim, bearer and all, so it has to
                // be one we would have asked. `api.spotify.com` answers `next`
                // absolutely; a link elsewhere is not followed.
                ?.takeIf { it.startsWith(API) }
        }
        if (tracks.size >= MAX_TRACKS && total > MAX_TRACKS) {
            TrackLog.w(TAG, "${ref.id} has $total tracks; read the first $MAX_TRACKS")
        }
        return tracks to total
    }

    private fun SpotifyRef.path(kind: SpotifyRef.Kind) = when (kind) {
        SpotifyRef.Kind.PLAYLIST -> "playlists"
        SpotifyRef.Kind.ALBUM -> "albums"
    }

    // ---- wire shapes ------------------------------------------------------

    @Serializable
    internal data class RawCollection(
        val name: String = "",
        val images: List<RawImage> = emptyList(),
    )

    @Serializable
    internal data class RawPage(
        val items: List<RawItem> = emptyList(),
        val next: String? = null,
        val total: Int = 0,
    )

    /**
     * A page entry. On a playlist this is a wrapper around the track; on an album
     * the track is the entry, because that endpoint has nothing to wrap it in.
     * Both are declared here rather than as two types because the fields are the
     * same ones and only their depth differs.
     */
    @Serializable
    internal data class RawItem(
        val track: RawTrack? = null,
        val id: String? = null,
        val name: String? = null,
        val artists: List<RawArtist> = emptyList(),
        @SerialName("duration_ms") val durationMs: Int? = null,
        val explicit: Boolean? = null,
        val album: RawAlbum? = null,
    ) {
        fun asTrack(): RawTrack? {
            track?.let { return it }
            val id = id ?: return null
            val name = name ?: return null
            return RawTrack(id, name, artists, durationMs, explicit, album)
        }
    }

    @Serializable
    internal data class RawTrack(
        val id: String,
        val name: String,
        val artists: List<RawArtist> = emptyList(),
        @SerialName("duration_ms") val durationMs: Int? = null,
        val explicit: Boolean? = null,
        val album: RawAlbum? = null,
    ) {
        /**
         * Null for anything that isn't a music track, which in practice means a
         * podcast episode — a playlist can hold those, and they arrive carrying a
         * name and a duration much like a track does. The artist is the
         * discriminator: a track always has one and an episode never does.
         */
        fun resolve(album: RawCollection?): SpotifyTrack? {
            val artist = artists.joinToString(", ") { it.name }.trim().ifBlank { return null }
            val release = this.album?.name ?: album?.name
            return SpotifyTrack(
                id = id,
                title = name.trim().ifBlank { return null },
                artist = artist,
                album = release?.takeIf { it.isNotBlank() },
                durationSec = durationMs?.div(1000)?.takeIf { it > 0 },
                artworkUrl = this.album?.images?.largestUrl() ?: album?.images?.largestUrl(),
                isExplicit = explicit == true,
            )
        }
    }

    @Serializable
    internal data class RawArtist(val name: String = "")

    @Serializable
    internal data class RawAlbum(
        val name: String = "",
        val images: List<RawImage> = emptyList(),
    )

    @Serializable
    internal data class RawImage(
        val url: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    /**
     * Spotify lists a release's covers smallest first, and the smallest is 64px —
     * fine as a list row, useless as the header of the playlist it came from.
     */
    internal fun List<RawImage>?.largestUrl(): String? =
        this?.filter { !it.url.isNullOrBlank() }?.maxByOrNull { it.width ?: 0 }?.url
}
