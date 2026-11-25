package fr.spotifeur.playback

import android.content.Context
import fr.spotifeur.data.AudioQuality
import fr.spotifeur.data.Music
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.isLocalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a Spotify track's audio comes from.
 *
 * Spotify Free only gets AAC 128 kbit/s from the engine and no downloads. On such an account (and without any
 * Spotify account) a Spotify track plays and downloads from its best matching copy on YouTube Music, Opus at about
 * 160 kbit/s, found by [fr.spotifeur.data.youtube.TrackMatcher]. A track no copy matches plays through Spotify's
 * engine, which is always the exact recording. Accounts the engine serves above 128 kbit/s keep the engine.
 */
internal object AudioRoute {
    private const val PREFS = "audio_route"
    private const val LIMITED = "account_limited"
    @Volatile private var app: Context? = null
    /** True when the signed-in account is held to [AudioQuality.NORMAL_BANDWIDTH]; kept for offline starts. */
    @Volatile var accountLimited: Boolean = true
        private set
    /**
     * False when Spotify's key service gives this account no keys. What decides it is the `offline` entitlement
     * (protocol/08-native-engine.md, 2026-11-10): Premium and a new Free account's introductory period have it, the
     * usual Free plan does not, and `playplay/v1/key` then answers 403 even at 128 kbit/s. The engine then plays only
     * the clear lead-in, so it is never used as a fallback. A refusal seen at run time settles it for the session.
     */
    @Volatile var onDemand: Boolean = true
        private set
    /** Spotify tracks that have no copy, for this process: they go straight to the engine. */
    private val engineOnly = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (app != null) return
        val appContext = context.applicationContext
        app = appContext
        accountLimited = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LIMITED, true)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { refresh(appContext) }
    }

    /** Reads the account's entitlements once the session is up; a plan change takes effect from the next track. */
    suspend fun refresh(context: Context) {
        if (!SpotifyAccount.signedIn && runCatching { SpotifyAccount.restore(context) }.getOrNull() == null) return
        val limit = runCatching { SpotifyAccount.require(context).maximumAudioBandwidth() }.getOrNull() ?: return
        accountBandwidthKnown(limit)
    }

    fun accountBandwidthKnown(limit: Long) {
        val limited = limit <= AudioQuality.NORMAL_BANDWIDTH
        if (limited == accountLimited) return
        accountLimited = limited
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(LIMITED, limited)?.apply()
    }

    fun attributesKnown(attributes: Map<String, String>) {
        onDemand = attributes["type"] == "premium" || attributes["offline"] == "true"
    }

    fun engineRefused() { onDemand = false }

    /** True when [track], a Spotify track, should be read from its matched copy rather than from the engine. */
    fun viaCopy(track: Music, spotifySignedIn: Boolean): Boolean =
        viaCopy(track.id, track.isLocalTrack, spotifySignedIn, accountLimited)

    internal fun viaCopy(trackId: String, local: Boolean, spotifySignedIn: Boolean, limited: Boolean): Boolean = when {
        local || !trackId.startsWith("spotify:track:") -> false
        !spotifySignedIn -> true
        trackId in engineOnly && onDemand -> false
        else -> limited
    }

    /** No copy of [trackId] could be opened: it plays through the engine from now on (this process). */
    fun noCopy(trackId: String) { engineOnly += trackId }

    /** True when [trackId] may fall back to the engine: only with a Spotify account. */
    fun canFallBackToEngine(trackId: String): Boolean = SpotifyAccount.signedIn && onDemand && trackId.startsWith("spotify:track:")
}
