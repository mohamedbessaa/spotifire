package fr.spotifeur.data

import fr.spotifeur.data.model.Song
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds the Spotify twin of a song that is not a Spotify track (YouTube Music, local file), so it can borrow the
 * twin's Canvas. A twin is the same recording: same title and main artist, nearly the same length, and no version
 * the source does not claim to be (live, karaoke, remix...), because a clip made for another version would mislead.
 */
object CanvasTwin {
    private const val MAX_DRIFT_MS = 3000
    private val versionWords = listOf("live", "karaoke", "remix", "instrumental", "acoustic", "sped up", "slowed")
        .map { it to Regex("\\b$it\\b") }
    private val cache = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** The `spotify:track:` URI among [candidates] that is the same recording, or null. */
    fun pick(title: String, artist: String, durationMs: Long, candidates: List<Music>): String? {
        val core = core(title)
        val raw = title.lowercase(Locale.ROOT)
        val main = fold(artists(artist).firstOrNull() ?: return null)
        if (core.isEmpty()) return null
        return candidates.firstOrNull { candidate ->
            candidate.kind == MusicKind.Track && candidate.id.startsWith("spotify:track:") &&
                core(candidate.title) == core &&
                artists(candidate.subtitle).any { fold(it) == main } &&
                Math.abs(candidate.durationMs - durationMs) <= MAX_DRIFT_MS &&
                sameVersion(candidate.title.lowercase(Locale.ROOT), raw)
        }?.id
    }

    /** Searches Spotify for [song]'s twin, once per song: a miss is remembered too. */
    internal suspend fun find(song: Song, search: SpotifySearch): String? {
        cache[song.id]?.let { return it.ifEmpty { null } }
        val page = try {
            search.search("${song.title} ${song.artist}", Locale.ENGLISH, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.i("SpotifeurCanvas", "twin search failed: ${failure.javaClass.simpleName}")
            return null
        }
        val twin = pick(song.title, song.artist, song.duration, page.items)
        cache[song.id] = twin ?: NONE
        return twin
    }

    /** Both titles name the same versions: a remix source must not borrow the original's clip either. */
    private fun sameVersion(candidate: String, source: String) =
        versionWords.all { (_, word) -> word.containsMatchIn(candidate) == word.containsMatchIn(source) }

    private fun artists(text: String) = text.split(",", "&", " feat.", " ft.", " x ").map(String::trim).filter(String::isNotEmpty)

    /** Title without brackets or anything after " - " (feat., remaster...), folded. */
    private fun core(title: String): String =
        fold(title.substringBefore(" - ").replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " "))

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
}
