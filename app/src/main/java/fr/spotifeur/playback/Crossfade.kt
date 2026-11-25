package fr.spotifeur.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A real crossfade: the outgoing track keeps playing while the next one rises, as Spotify does.
 *
 * One ExoPlayer renders one item at a time, so an overlap needs a second player (the idea of BitChord's
 * `CrossfadeController`, GPLv3). The two players are peers: [active] backs the MediaSession, [standby] is idle.
 * A few seconds before the fade, the standby is loaded with the whole queue positioned on the next track and
 * prepared silently. When the fade starts it plays, the session moves to it at once ([onHandoff]: the title, the
 * notification and the queue follow the song that is starting, like Spotify), and the outgoing player keeps only its
 * current item, so it can never move on and play the next track a second time. Volumes follow the equal-power law
 * (`cos`/`sin`, constant power through the blend), then the outgoing player is stopped and becomes the standby.
 *
 * A fade is never forced: no known duration, repeat-one, a user seek or skip, or an incoming track that is not ready in
 * time all leave the queue to move on plainly, a missed crossfade rather than a broken one.
 */
@UnstableApi
internal class Crossfade(
    private val scope: CoroutineScope,
    initialActive: ExoPlayer,
    initialStandby: ExoPlayer,
    /** Fade length in ms; 0 turns crossfading off. Read at every tick. */
    private val fadeMs: () -> Long,
    /** True when both items may overlap (two encrypted engine decoders at once are not proven yet). */
    private val canOverlap: (outgoing: MediaItem, incoming: MediaItem) -> Boolean,
    /** Automix: blend where the outgoing song winds down (its outro) instead of at a fixed time. */
    private val automix: () -> Boolean = { false },
    /** True when [player]'s song has reached its outro, measured on what it renders. */
    private val outroOf: (ExoPlayer) -> Boolean = { false },
    /** Moves the session, listeners and helpers from [outgoing] to [incoming]. Called on the main thread. */
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
) : AutoCloseable {
    var active: ExoPlayer = initialActive
        private set
    var standby: ExoPlayer = initialStandby
        private set
    private var armedFor: String? = null
    private var fading: Job? = null
    private var ticker: Job? = null

    fun start() {
        ticker = scope.launch {
            while (isActive) { tick(); delay(TICK_MS) }
        }
    }

    private fun tick() {
        if (fading != null) return
        val auto = automix()
        val fade = if (auto) AUTOMIX_FADE_MS else fadeMs().coerceIn(0, MAX_FADE_MS)
        // Automix listens for the outro over the last stretch of the song, then blends from there.
        val window = if (auto) AUTOMIX_WINDOW_MS else fade
        val player = active
        val next = player.nextMediaItemIndex
        val duration = player.duration
        if (fade == 0L || !player.isPlaying || next == C.INDEX_UNSET || duration == C.TIME_UNSET ||
            player.repeatMode == Player.REPEAT_MODE_ONE || duration < fade * 2) { disarm(); return }
        val remaining = duration - player.currentPosition
        val outgoing = player.currentMediaItem ?: return
        val incoming = player.getMediaItemAt(next)
        if (!canOverlap(outgoing, incoming)) { disarm(); return }
        val key = "${outgoing.mediaId}>${incoming.mediaId}#$next"
        if (remaining <= window + ARM_LEAD_MS && armedFor != key) arm(player, next, key, auto)
        val due = remaining <= fade || (auto && remaining <= window && outroOf(player))
        if (due && armedFor == key) {
            if (standby.playbackState == Player.STATE_READY) begin(player, minOf(remaining, fade))
            else if (remaining < fade / 2) disarm()
        }
    }

    /** Loads the whole queue on the standby, positioned on the next track, silent and paused. */
    private fun arm(player: ExoPlayer, next: Int, key: String, auto: Boolean = false) {
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        standby.run {
            stop(); volume = 0f; playWhenReady = false
            repeatMode = player.repeatMode
            playbackParameters = player.playbackParameters
            // Automix skips the incoming song's silent intro while it rises.
            skipSilenceEnabled = player.skipSilenceEnabled || auto
            setMediaItems(items, next, 0L)
            prepare()
        }
        armedFor = key
    }

    private fun disarm() {
        if (armedFor == null) return
        armedFor = null
        standby.stop(); standby.clearMediaItems()
    }

    private var automixSkip = false

    private fun begin(outgoing: ExoPlayer, remainingMs: Long) {
        val incoming = standby
        automixSkip = incoming.skipSilenceEnabled && !outgoing.skipSilenceEnabled
        armedFor = null
        incoming.volume = 0f
        incoming.play()
        active = incoming; standby = outgoing
        onHandoff(outgoing, incoming)
        // The outgoing player only finishes its own track.
        val current = outgoing.currentMediaItemIndex
        if (current + 1 < outgoing.mediaItemCount) outgoing.removeMediaItems(current + 1, outgoing.mediaItemCount)
        if (current > 0) outgoing.removeMediaItems(0, current)
        outgoing.repeatMode = Player.REPEAT_MODE_OFF
        val length = remainingMs.coerceAtLeast(MIN_FADE_MS)
        val startedId = incoming.currentMediaItem?.mediaId
        fading = scope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                while (isActive) {
                    val t = ((android.os.SystemClock.elapsedRealtime() - started).toFloat() / length).coerceIn(0f, 1f)
                    outgoing.volume = gainOut(t); incoming.volume = gainIn(t)
                    // A skip, seek or pause of the incoming track ends the blend at once.
                    if (t >= 1f || !incoming.playWhenReady || incoming.currentMediaItem?.mediaId != startedId) break
                    delay(FRAME_MS)
                }
            } finally {
                incoming.volume = 1f
                incoming.skipSilenceEnabled = outgoing.skipSilenceEnabled && !automixSkip
                outgoing.stop(); outgoing.clearMediaItems(); outgoing.volume = 1f
                fading = null
            }
        }
    }

    /** Ends any blend at once, for a user command on the session (skip, seek, new queue). */
    fun interrupt() { fading?.cancel(); disarm() }

    override fun close() {
        ticker?.cancel(); fading?.cancel()
    }

    internal companion object {
        const val MAX_FADE_MS = 12_000L
        const val AUTOMIX_FADE_MS = 10_000L
        /** How long before the end Automix listens for the outro. */
        const val AUTOMIX_WINDOW_MS = 20_000L
        const val MIN_FADE_MS = 500L
        /** Head start the standby gets to open the next track and fill its buffer. */
        const val ARM_LEAD_MS = 5_000L
        const val TICK_MS = 100L
        const val FRAME_MS = 20L
        fun gainOut(t: Float): Float = cos(t * PI / 2).toFloat()
        fun gainIn(t: Float): Float = sin(t * PI / 2).toFloat()
    }
}
