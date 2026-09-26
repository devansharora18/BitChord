package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * One track as Spotify's embed page describes it — identity and metadata, never
 * audio. [album] is null for a playlist: the embed carries no per-track release,
 * so only an album import, where the page's own subject is the release, can fill
 * it in.
 */
data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val isExplicit: Boolean,
)

/** A playlist or album, and as much of its track list as [TRACK_LIMIT] allows. */
data class SpotifyCollection(
    val title: String,
    val owner: String?,
    val artworkUrl: String?,
    val tracks: List<SpotifyTrack>,
    /**
     * That [TRACK_LIMIT] tracks came back, which means there are probably more
     * behind them.
     *
     * An inference and not a fact: the page carries no total anywhere, so the
     * only thing known is that the cap was reached. It is here so the UI can say
     * "Spotify returned the most it gives" rather than implying the list is
     * complete, which for a 1,000-track playlist would be a lie.
     */
    val atTrackLimit: Boolean,
)

/**
 * Why a fetch did or didn't produce a collection.
 *
 * [Unreadable] is the one worth distinguishing: the link named nothing, named
 * something private, or named a single track — all of which look identical from
 * here, and none of which is worth retrying.
 */
sealed interface SpotifyFetch {
    data class Loaded(val collection: SpotifyCollection) : SpotifyFetch
    data object Unreadable : SpotifyFetch
    data object Failed : SpotifyFetch
}

/**
 * Reads a playlist or album off Spotify's public embed page.
 *
 * ```
 * GET https://open.spotify.com/embed/playlist/<id>
 * ```
 *
 * The page a third-party site frames its little player from, and it carries the
 * whole track list as JSON in a `<script id="__NEXT_DATA__">` tag — no client
 * key, no OAuth, no login, no cookie. The same request serves a browser and a
 * plain client; there is nothing to impersonate.
 *
 * ### What this costs, stated plainly
 *
 * **It is undocumented.** This reads a page, not an interface Spotify promises to
 * keep, and it can change without notice. So every failure mode here ends in a
 * message rather than a crash, and nothing in the app may depend on a field being
 * there — see [TRACK_LIMIT] for the limit that is already known.
 *
 * **It serves no audio**, and neither does any Spotify API. What arrives is a
 * list of tracks; each is then matched against a catalogue that will serve it,
 * and playback resolves as it does everywhere else. Every track also carries a
 * 30-second 96kbps `audioPreview`, which is deliberately not read: a preview is
 * not the recording, and importing one would be a silent quality downgrade.
 */
internal object SpotifyEmbed {

    private const val TAG = "SpotifyEmbed"
    private const val EMBED_URL = "https://open.spotify.com/embed"

    /**
     * A plain app UA, not a browser's. The embed page answers a client that
     * identifies itself honestly, and pretending otherwise would buy nothing —
     * measured: the page answers identically for an honest UA and for a browser's.
     */
    private const val UA = "BitChord/1.0 (com.music.bitchord; Android)"

    /**
     * The most tracks the embed page will ever return, and there is no cursor to
     * ask for more. Measured: a 151-track playlist, a 1,000-track one and a
     * 10,000-track one all answer with exactly 100, while a 37-track playlist
     * answers whole.
     *
     * The page carries no track count either, so a truncated read cannot be
     * detected from the response — only the fact of having hit this number.
     */
    const val TRACK_LIMIT = 100

    internal val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    suspend fun fetch(ref: SpotifyRef): SpotifyFetch = withContext(Dispatchers.IO) {
        // A bare id names either kind and the page is what settles it, so both
        // are tried rather than one being guessed at. The first is the common
        // case and usually answers.
        val kinds = when (ref.kind) {
            SpotifyRef.Kind.UNKNOWN -> listOf(SpotifyRef.Kind.PLAYLIST, SpotifyRef.Kind.ALBUM)
            else -> listOf(ref.kind)
        }
        var failed = false
        for (kind in kinds) {
            when (val attempt = read(ref.id, kind)) {
                is SpotifyFetch.Loaded -> return@withContext attempt
                SpotifyFetch.Unreadable -> if (ref.kind != SpotifyRef.Kind.UNKNOWN) {
                    return@withContext SpotifyFetch.Unreadable
                }
                // One kind failing at the transport says nothing about the other,
                // so the second is still worth asking.
                SpotifyFetch.Failed -> failed = true
            }
        }
        if (failed) SpotifyFetch.Failed else SpotifyFetch.Unreadable
    }

    private fun read(id: String, kind: SpotifyRef.Kind): SpotifyFetch {
        val page = get("$EMBED_URL/${kind.name.lowercase()}/$id")
            ?: return SpotifyFetch.Failed.also { TrackLog.w(TAG, "embed request for $id threw or timed out") }

        // The payload is a script tag inside an HTML page, so it has to be cut out
        // before it can be parsed. Its contents are valid JSON exactly as they
        // stand — no unescaping — which is checked rather than assumed, since a
        // page that started escaping them would decode into nonsense quietly.
        val script = payloadOf(page)
            ?: return SpotifyFetch.Unreadable.also { TrackLog.w(TAG, "$id: no __NEXT_DATA__ in the embed page") }

        val entity = runCatching {
            json.decodeFromString<Page>(script).props.pageProps.state.data.entity
        }.getOrNull()?.takeIf { it.type == kind.name.lowercase() }
        // A link that names nothing still answers 200, with this path simply
        // absent. Read as unreadable rather than as an empty playlist, which is
        // the difference between a message and a playlist with nothing in it.
            ?: return SpotifyFetch.Unreadable.also {
                TrackLog.w(TAG, "$id is not a readable ${kind.name.lowercase()}")
            }

        // An album's own name is its release, so it is filled across the page.
        // A playlist has no per-track release on this page at all.
        val album = entity.name.takeIf { kind == SpotifyRef.Kind.ALBUM && it.isNotBlank() }
        val tracks = entity.tracks.mapNotNull { it.resolve(album) }
        if (tracks.isEmpty()) {
            TrackLog.w(TAG, "$id yielded no usable tracks")
            return SpotifyFetch.Unreadable
        }
        TrackLog.d(TAG, "read ${tracks.size} tracks from '${entity.name}'")
        return SpotifyFetch.Loaded(
            SpotifyCollection(
                title = entity.name.trim().ifBlank { id },
                // The owner's name, on both kinds: for an album this is the artist.
                owner = entity.subtitle.trim().takeIf { it.isNotBlank() },
                artworkUrl = entity.coverArt?.sources?.largestUrl(),
                tracks = tracks,
                atTrackLimit = entity.tracks.size >= TRACK_LIMIT,
            ),
        )
    }

    /** One GET, or null. Shares [Http.client] so it reuses the app's pool. */
    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

    private val PAYLOAD =
        Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The JSON out of the page, or null when the page carries no such script.
     *
     * Internal for the same reason the DTOs are: this is the seam that decides
     * whether an import is an empty screen or a message, and it is the step a
     * fixture that starts at the JSON silently skips.
     */
    internal fun payloadOf(page: String): String? = PAYLOAD.find(page)?.groupValues?.get(1)

    // ---- wire shapes ------------------------------------------------------
    // Not private: a field Spotify renames is an empty import rather than an
    // error, and this is the one piece here reachable without a device. The
    // fixtures in SpotifyEmbedTest are real captures, so a change in the page
    // shows up as a failing test instead of as silence.

    @Serializable
    internal data class Page(val props: Props = Props())

    @Serializable
    internal data class Props(@SerialName("pageProps") val pageProps: PageProps = PageProps())

    @Serializable
    internal data class PageProps(val state: State = State())

    @Serializable
    internal data class State(val data: Data = Data())

    @Serializable
    internal data class Data(val entity: Entity = Entity())

    @Serializable
    internal data class Entity(
        val type: String = "",
        val name: String = "",
        /** The owner at this level and the artist one level down — the page uses one name for both. */
        val subtitle: String = "",
        @SerialName("trackList") val tracks: List<Track> = emptyList(),
        @SerialName("coverArt") val coverArt: CoverArt? = null,
    )

    @Serializable
    internal data class Track(
        val title: String = "",
        val subtitle: String = "",
        val duration: Long = 0L,
        val uri: String = "",
        @SerialName("entityType") val entityType: String = "",
        @SerialName("isExplicit") val isExplicit: Boolean = false,
        @SerialName("isPlayable") val isPlayable: Boolean = true,
    ) {
        /**
         * Null for anything that is not a playable music track, which is three
         * separate exclusions and not one:
         *
         *  - **Episodes.** A playlist can hold podcast episodes, and they carry a
         *    title and a duration much like a track. [entityType] names them.
         *  - **Region blocks and removals.** [isPlayable] is false with a reason
         *    for tracks that are simply not available here; importing one
         *    produces a row that can never play.
         *  - **Unnamed entries.** A title or artist that came back blank.
         *
         * The artist test is a last resort, not the discriminator — a track
         * always has one, but relying on that alone would quietly drop
         * everything else above if the page ever renames those fields.
         */
        fun resolve(album: String?): SpotifyTrack? {
            if (entityType.isNotBlank() && entityType != "track") return null
            if (!isPlayable) return null
            val title = title.trim()
            val artist = subtitle.trim()
            if (title.isEmpty() || artist.isEmpty()) return null
            return SpotifyTrack(
                id = uri.substringAfterLast(':').takeIf { it.isNotBlank() } ?: title,
                title = title,
                artist = artist,
                album = album,
                durationSec = duration.div(1000).toInt().takeIf { it > 0 },
                isExplicit = isExplicit,
            )
        }
    }

    @Serializable
    internal data class CoverArt(val sources: List<Image> = emptyList())

    @Serializable
    internal data class Image(val url: String = "", val width: Int = 0)

    /**
     * The page offers each cover at three sizes, smallest first. The smallest is
     * 60px, which is fine as a list row and useless as the header of the
     * collection it came from.
     */
    internal fun List<Image>?.largestUrl(): String? =
        this?.filter { it.url.isNotBlank() }?.maxByOrNull { it.width }?.url
}
