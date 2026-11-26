package fr.spotifeur.playback

import android.content.Context

/** Bytes of a copy held on the phone, read safely: SimpleCache answers -length for a name it does not hold. */
internal object CopyBytes {
    fun kept(recent: Long, downloaded: Long): Long = recent.coerceAtLeast(0) + downloaded.coerceAtLeast(0)

    /** Held under [key], in the recent-audio cache and among downloads. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun kept(context: Context, key: String): Long =
        kept(AudioCache.cache(context).getCachedBytes(key, 0, Long.MAX_VALUE), downloaded(context, key))

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun downloaded(context: Context, key: String): Long = DownloadCenter.cache(context).getCachedBytes(key, 0, Long.MAX_VALUE).coerceAtLeast(0)
}
