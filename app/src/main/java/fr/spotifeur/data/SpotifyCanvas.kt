package fr.spotifeur.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Canvas, the short looping videos artists set for their tracks, as the official clients read them:
 * `POST spclient/canvaz-cache/v0/canvases` with an `EntityCanvazRequest` (1 entities: 1 entity_uri) and an
 * `EntityCanvazResponse` back (1 canvases: 2 url, 4 type, 5 entity_uri). Types 1 to 3 are videos (looping or not);
 * 0 is a still image and 4 a GIF, which the player does not show.
 */
object SpotifyCanvas {
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** The MP4 of [trackUri]'s canvas, null when it has none or no Spotify session is open. */
    suspend fun videoFor(trackUri: String): String? {
        if (!trackUri.startsWith("spotify:track:")) return null
        cache[trackUri]?.let { return it.ifEmpty { null } }
        val session = SpotifyAccount.current() ?: return null
        val body = proto { writeByteArray(1, proto { writeString(1, trackUri) }) }
        val url = runCatching {
            decode(authenticatedSpotifyRequest(SpotifyAccount.transport, session, spotifyUrl("canvaz-cache/v0/canvases"), "Canvas", body))
        }.onFailure { android.util.Log.i("SpotifeurCanvas", "canvas unavailable for $trackUri: ${it.javaClass.simpleName}") }
            .getOrElse { return null }[trackUri]
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
