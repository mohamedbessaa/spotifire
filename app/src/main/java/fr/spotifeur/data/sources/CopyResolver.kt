package fr.spotifeur.data.sources

import android.content.Context
import androidx.core.content.edit
import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher

/** The copy a Spotify track is read from when it does not go through Spotify's engine. */
sealed interface Copy {
    /** Monochrome's track service: same ISRC, AAC 320 kbit/s. */
    data class Monochrome(val id: String) : Copy
    /** YouTube Music, matched by [TrackMatcher]: Opus about 160 kbit/s. */
    data class YouTube(val videoId: String) : Copy
}

/**
 * Chooses the best copy of a track, best first: the same recording on Monochrome (by ISRC), then YouTube Music.
 * The choice is kept on the device, so a track always reads the same copy: its cached bytes belong to one file.
 */
object CopyResolver {
    private var app: Context? = null
    fun init(context: Context) { if (app == null) app = context.applicationContext }
    private val preferences get() = app?.getSharedPreferences("track_copies", Context.MODE_PRIVATE)

    /** Blocking. Null when no copy was found. */
    fun resolve(query: MatchQuery): Copy? {
        preferences?.getString(query.key, null)?.let(::decode)?.let { kept ->
            if (kept is Copy.YouTube) lookForUpgrade(query)
            return kept
        }
        // A match kept by an earlier version may already have bytes in the cache under this track's key.
        TrackMatcher.known(query.key)?.let { return Copy.YouTube(it) }
        // A track loaded from an older cache has no ISRC: read it from Spotify's metadata, the key to an exact copy.
        val known = if (query.isrc == null && query.key.startsWith("spotify:track:")) query.copy(isrc = isrcOf(query.key)) else query
        val copy = MonochromeTracks.find(known)?.let { Copy.Monochrome(it) }
            ?: TrackMatcher.resolve(known)?.let { Copy.YouTube(it) }
            ?: return null
        android.util.Log.i("SpotifeurCopy", "${query.key} isrc=${known.isrc} copy=$copy")
        preferences?.edit { putString(query.key, encode(copy)) }
        return copy
    }

    fun forget(key: String) = preferences?.edit { remove(key) }

    private val checking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * A track kept on its YouTube Music copy may have an exact copy on Monochrome (an older run without its ISRC, a
     * service that was down). Look once, in the background; a find is applied at the next start, before anything plays,
     * since the cached bytes of the old copy sit under the same key.
     */
    private fun lookForUpgrade(query: MatchQuery) {
        val prefs = preferences ?: return
        if (prefs.getBoolean(CHECKED + query.key, false) || !checking.add(query.key)) return
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            try {
                val known = if (query.isrc == null && query.key.startsWith("spotify:track:")) query.copy(isrc = isrcOf(query.key)) else query
                val better = MonochromeTracks.find(known)
                prefs.edit {
                    putBoolean(CHECKED + query.key, true)
                    if (better != null) putString(UPGRADE + query.key, better)
                }
                android.util.Log.i("SpotifeurCopy", "${query.key} upgrade=${better ?: "none"}")
            } finally { checking.remove(query.key) }
        }
    }

    /** Applies the upgrades found earlier: drops the old copy's cached bytes, then reads the better one. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun applyUpgrades(context: android.content.Context) {
        val prefs = preferences ?: return
        val pending = prefs.all.keys.filter { it.startsWith(UPGRADE) }
        if (pending.isEmpty()) return
        val downloads = fr.spotifeur.playback.DownloadCenter.cache(context)
        prefs.edit {
            pending.forEach { entry ->
                val key = entry.removePrefix(UPGRADE)
                val id = prefs.getString(entry, null)
                remove(entry)
                // A downloaded song keeps the copy it was downloaded as.
                val kept = downloads.getCachedBytes(fr.spotifeur.playback.YouTubeUris.cacheKey(key), 0, Long.MAX_VALUE)
                if (id != null && kept <= 0) {
                    fr.spotifeur.playback.AudioCache.remove(context, listOf(key))
                    // The copy's bytes sit under the track's copy key, whatever file the index last described.
                    fr.spotifeur.playback.AudioCache.cache(context).removeResource(fr.spotifeur.playback.YouTubeUris.cacheKey(key))
                    putString(key, MonochromeTracks.CHOICE_PREFIX + id)
                    android.util.Log.i("SpotifeurCopy", "$key upgraded to Monochrome $id")
                }
            }
        }
    }

    private const val CHECKED = "checked:"
    private const val UPGRADE = "upgrade:"

    /**
     * The Monochrome copy of [query] could not be opened before any of its bytes were kept: use the YouTube Music copy
     * from now on. Only safe while nothing of the first copy is cached under the track's key.
     */
    fun demote(query: MatchQuery): Copy.YouTube? {
        val videoId = TrackMatcher.resolve(query) ?: return null
        preferences?.edit { putString(query.key, videoId) }
        return Copy.YouTube(videoId)
    }

    private fun isrcOf(uri: String): String? = runCatching {
        kotlinx.coroutines.runBlocking { fr.spotifeur.data.SpotifyCatalog.current?.metadataBytes(uri) }
            ?.let { fr.spotifeur.data.metadataIsrc(fr.spotifeur.data.Proto(it)) }
    }.getOrNull()

    internal fun encode(copy: Copy): String = when (copy) {
        is Copy.Monochrome -> MonochromeTracks.CHOICE_PREFIX + copy.id
        is Copy.YouTube -> copy.videoId
    }

    internal fun decode(value: String): Copy? = when {
        value.isBlank() -> null
        value.startsWith(MonochromeTracks.CHOICE_PREFIX) -> Copy.Monochrome(value.removePrefix(MonochromeTracks.CHOICE_PREFIX))
        else -> Copy.YouTube(value)
    }
}
