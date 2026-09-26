package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song

/**
 * A read playlist, matched and waiting to be confirmed.
 *
 * Nothing is written until this is confirmed. An import is a hundred catalogue
 * searches against someone else's library, and the answer is worth seeing before
 * it becomes a playlist — especially the tracks that found nothing.
 */
data class SpotifyImportDraft(
    val title: String,
    val artworkUrl: String?,
    val matches: List<SpotifyMatch>,
    /**
     * That [SpotifyEmbed.TRACK_LIMIT] tracks came back, so there are probably
     * more behind them. Carried so this can be said plainly rather than letting
     * a truncated list read as the whole playlist.
     */
    val atTrackLimit: Boolean,
) {
    val matched: List<Song> get() = matches.mapNotNull { it.song }

    /** Kept in the draft rather than dropped, so the shortfall can be shown. */
    val missed: List<SpotifyMatch> get() = matches.filter { it.song == null }
}

/**
 * Where an import has got to, from the Library tile to the finished playlist.
 *
 * [Failure] is a reason rather than a message on purpose: the wording belongs to
 * the surface that shows it, and every string in this app is translated into
 * seventeen languages. Deciding here would mean this file choosing English.
 */
sealed interface SpotifyImportState {

    data object Idle : SpotifyImportState

    /** Fetching the track list. A single request, so this is brief. */
    data object Reading : SpotifyImportState

    /** Asking the catalogue about each track, which is the long part. */
    data class Matching(val done: Int, val total: Int) : SpotifyImportState

    data class Ready(val draft: SpotifyImportDraft) : SpotifyImportState

    /** Writing the playlist. */
    data class Importing(val done: Int, val total: Int) : SpotifyImportState

    /**
     * The playlist exists. [added] is below [requested] when a batch was refused,
     * which is stated rather than smoothed over — a silent shortfall would leave
     * the listener believing a track they can see on Spotify is in a playlist
     * that does not hold it.
     */
    data class Done(val playlistId: String, val added: Int, val requested: Int) : SpotifyImportState

    data class Failed(val reason: Failure) : SpotifyImportState

    enum class Failure {
        /** The text pasted is not a Spotify link this can read. */
        BadLink,

        /** A link that names nothing, or something not public. */
        Unreadable,

        /** The request never landed, or Spotify answered with something else. */
        Unreachable,

        /** Nothing in the catalogue was confidently the same recording. */
        NothingMatched,

        /** YouTube Music refused the playlist itself, so there is nothing to show. */
        WriteFailed,
    }
}
