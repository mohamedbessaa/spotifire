package fr.spotifeur.data.sources

import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The track service of Monochrome (monochrome.st, Apache-2.0), used as a copy of Spotify tracks.
 *
 * Measured 2026-11-25: `GET /search/tracks?q=…` answers in about a second with each track's ISRC and length, and
 * `GET /track/{id}` serves an MP4 with AAC-LC 320 kbit/s at 44.1 kHz, with byte ranges, no account. Only a result
 * with the **same ISRC** as the Spotify track is taken: the same recording, never a cover or another version.
 */
object MonochromeTracks {
    const val BASE = "https://tracks.monochrome.st"
    /** Prefix of a choice kept by [TrackMatcher]'s store, so a track always reads the same copy (one cache key). */
    const val CHOICE_PREFIX = "mono:"
    const val BITRATE = 320_000

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // A track the service has not served lately is fetched upstream first: its first byte can take a while.
        .readTimeout(30, TimeUnit.SECONDS).build()

    data class Candidate(val id: String, val title: String, val isrc: String, val durationMs: Int, val playable: Boolean)

    /** Blocking. The id of the track with the query's ISRC, or null (no ISRC, no such track, service down). */
    fun find(query: MatchQuery): String? {
        val isrc = query.isrc?.takeIf { it.length == 12 } ?: return null
        val artist = query.artists.split(",").firstOrNull()?.trim().orEmpty()
        val text = "${query.title} $artist".trim()
        val candidates = runCatching { search(text) }.getOrDefault(emptyList())
        return pick(isrc, query.durationMs, candidates)?.id
    }

    /** The candidate with exactly [isrc] that can be played, closest in length to the wanted one. */
    internal fun pick(isrc: String, durationMs: Int, candidates: List<Candidate>): Candidate? = candidates
        .filter { it.playable && it.isrc.equals(isrc, ignoreCase = true) }
        .minByOrNull { if (durationMs > 0 && it.durationMs > 0) kotlin.math.abs(it.durationMs - durationMs) else 0 }

    internal fun search(text: String): List<Candidate> {
        val url = "$BASE/search/tracks".toHttpUrl().newBuilder().addQueryParameter("q", text)
            .addQueryParameter("limit", "20").build()
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            return parse(response.body?.string().orEmpty())
        }
    }

    internal fun parse(json: String): List<Candidate> {
        val tracks = JSONObject(json).optJSONArray("tracks") ?: return emptyList()
        return (0 until tracks.length()).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            val id = track.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            Candidate(id, track.optString("title"), track.optString("isrc"), track.optLong("duration").toInt(),
                track.optBoolean("playable", true))
        }
    }

    fun streamUrl(id: String): String = "$BASE/track/$id"
}
