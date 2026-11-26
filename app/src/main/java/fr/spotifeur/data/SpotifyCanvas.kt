package fr.spotifeur.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Canvas, the short looping videos artists set for their tracks, as the official clients read them:
 * `POST spclient/canvaz-cache/v0/canvases` with an `EntityCanvazRequest` (1 entities: 1 entity_uri) and an
 * `EntityCanvazResponse` back (1 canvases: 2 url, 4 type, 5 entity_uri). Types 1 to 3 are videos (looping or not);
 * 0 is a still image and 4 a GIF, which the player does not show.
 */
object SpotifyCanvas {
    /**
     * Parked: the clips are off and the player shows the track card. The code is kept for later; set this to true to
     * bring them back (the player and the Settings row follow it).
     */
    const val ENABLED = false

    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** The clip for [song]: its own canvas on Spotify, otherwise that of its Spotify twin, when one is found. */
    suspend fun videoFor(song: fr.spotifeur.data.model.Song): String? {
        if (song.id.startsWith("spotify:track:")) return videoFor(song.id)
        val session = SpotifyAccount.current() ?: return null
        val twin = CanvasTwin.find(song, SpotifySearch(SpotifyAccount.transport, session)) ?: return null
        return videoFor(twin)
    }

    /** The MP4 of [trackUri]'s canvas, null when it has none or no Spotify session is open. */
    suspend fun videoFor(trackUri: String): String? {
        if (!trackUri.startsWith("spotify:track:")) return null
        cache[trackUri]?.let { return it.ifEmpty { null } }
        val session = SpotifyAccount.current() ?: return null
        val body = proto { writeByteArray(1, proto { writeString(1, trackUri) }) }
        val url = runCatching {
            decode(authenticatedSpotifyRequest(SpotifyAccount.transport, session, spotifyUrl("canvaz-cache/v0/canvases"), "Canvas", body))
        }.onFailure { android.util.Log.i("SpotifeurCanvas", "canvas unavailable for $trackUri: ${it.javaClass.simpleName}") }
            .getOrElse { if (it is kotlin.coroutines.cancellation.CancellationException) throw it; return null }[trackUri]
        cache[trackUri] = url ?: NONE
        return url
    }

    /** Entity URI to video URL, for the videos in an `EntityCanvazResponse`. */
    internal fun decode(bytes: ByteArray): Map<String, String> = Proto(bytes).messages(1).mapNotNull { canvas ->
        val url = canvas.text(2)?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
        val type = canvas.number(4) ?: 0
        if (type !in 1..3) return@mapNotNull null
        (canvas.text(5) ?: return@mapNotNull null) to url
    }.toMap()
}
