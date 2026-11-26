// The limiter and gain glide are adapted from BitChord LoudnessProcessor.kt (GPLv3).
package fr.spotifeur.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import fr.spotifeur.data.AudioIndex
import fr.spotifeur.data.youtube.YTM_TRACK_PREFIX
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Loudness of tracks as their service publishes it. Spotify gives each file's integrated loudness
 * and true peak (AUDIO_FILES extension, kept in [AudioIndex]); YouTube Music gives `loudnessDb`,
 * the difference from its own reference, in the player response.
 */
object TrackLoudness {
    const val OFF = "off"
    const val QUIET = "quiet"
    const val NORMAL = "normal"
    const val LOUD = "loud"
    /** YouTube's reference loudness, in LUFS: its `loudnessDb` is the distance from it. */
    private const val YOUTUBE_REFERENCE_LUFS = -14f
    /** Headroom kept under 0 dBFS when a track is raised, except at the loud level. */
    private const val PEAK_CEILING_DB = -1f
    private const val MIN_GAIN_DB = -20f
    private const val MAX_GAIN_DB = 12f
    private val youtube = ConcurrentHashMap<String, Float>()

    fun rememberYouTube(videoId: String, loudnessDb: Float) { if (loudnessDb.isFinite()) youtube[videoId] = loudnessDb }

    /** Target loudness in LUFS of a level, null when off or unknown. */
    fun targetLufs(level: String): Float? = when (level) {
        QUIET -> -23f
        NORMAL -> -14f
        LOUD -> -11f
        else -> null
    }

    /** Gain to apply to [mediaId] in dB at [level], or null when off or its loudness is unknown (local files included). */
    fun gainDb(context: Context, mediaId: String, level: String): Float? {
        val target = targetLufs(level) ?: return null
        if (mediaId.startsWith(YTM_TRACK_PREFIX)) {
            val loudness = youtube[mediaId.removePrefix(YTM_TRACK_PREFIX)] ?: return null
            // `loudnessDb` is how far the track sits above YouTube's reference: +3 means -11 LUFS. Only that offset is
            // published, no peak: the limiter catches what the gain raises.
            return youTubeGainDb(loudness, target)
        }
        if (!mediaId.startsWith("spotify:track:")) return null
        val values = AudioIndex.get(context).loudness(mediaId) ?: return null
        return spotifyGainDb(values[0], values[1], target, limitByPeak = level != LOUD)
    }

    internal fun youTubeGainDb(loudnessDb: Float, targetLufs: Float): Float =
        (targetLufs - (YOUTUBE_REFERENCE_LUFS + loudnessDb)).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    internal fun spotifyGainDb(loudnessDb: Float, truePeakDb: Float, targetLufs: Float, limitByPeak: Boolean): Float? {
        if (!loudnessDb.isFinite()) return null
        val gain = targetLufs - loudnessDb
        val capped = if (limitByPeak && truePeakDb.isFinite()) min(gain, PEAK_CEILING_DB - truePeakDb) else gain
        return capped.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }
}

/**
 * Applies a linear gain, then a limiter. On float PCM (the normal path) the limiter works on the true peak with 2 ms of
 * lookahead and holds everything under -0.3 dBTP, whatever the effects before it added; on 16-bit PCM it is the older
 * sample-peak limiter at -1 dBFS (instant attack, ~150 ms release, no lookahead). [gain] is set from the main thread
 * and read on the playback thread; a change glides over ~50 ms so track changes do not click. Other encodings pass
 * through untouched.
 */
@UnstableApi
internal class LoudnessProcessor : fr.spotifeur.playback.dsp.TailingAudioProcessor() {
    @Volatile var gain: Float = 1f
    private var current = 1f
    private var reduction = 1f
    private var snap = true
    private var glideCoef = 1f
    private var releaseCoef = 1f

    private var limiter: fr.spotifeur.playback.dsp.TruePeakLimiter? = null
    private var frames = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        glideCoef = coefficient(GLIDE_SECONDS, inputAudioFormat.sampleRate)
        releaseCoef = coefficient(RELEASE_SECONDS, inputAudioFormat.sampleRate)
        limiter = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
            fr.spotifeur.playback.dsp.TruePeakLimiter(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        return inputAudioFormat
    }

    override fun onFlushed() { reduction = 1f; snap = true; limiter?.reset() }

    override fun onReset() { limiter = null; frames = FloatArray(0) }

    /** The float limiter holds its lookahead back; at the end of a stream it is let out (once, see the base class). */
    override fun writeTail() {
        val tail = limiter?.drain() ?: return
        replaceOutputBuffer(tail.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { tail.forEach { putFloat(it) } }.flip()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        limiter?.let { queueFloat(inputBuffer, it); return }
        val target = gain
        if (snap) { current = target; snap = false }
        val output = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        // Nothing to scale and nothing held down: copy the bytes as they are.
        if (target == 1f && abs(current - 1f) < SETTLED && reduction >= 1f - SETTLED) {
            current = 1f; reduction = 1f
            output.put(input).flip()
            return
        }
        val channels = inputAudioFormat.channelCount
        var g = current
        var gr = reduction
        while (input.remaining() >= 2 * channels) {
            g += (target - g) * glideCoef
            val start = input.position()
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(input.getShort(start + 2 * c).toInt()).toFloat())
            peak *= g
            val wanted = if (peak > LIMIT) LIMIT / peak else 1f
            gr = if (wanted < gr) wanted else gr + (wanted - gr) * releaseCoef
            val applied = g * gr
            // Truncation toward zero keeps the result at or under the ceiling.
            for (c in 0 until channels) output.putShort((input.short * applied).toInt().coerceIn(-32768, 32767).toShort())
        }
        output.put(input) // a trailing partial frame is not expected; pass it on unchanged
        current = g; reduction = gr
        output.flip()
    }

    private fun queueFloat(inputBuffer: ByteBuffer, limiter: fr.spotifeur.playback.dsp.TruePeakLimiter) {
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val channels = inputAudioFormat.channelCount
        val count = input.remaining() / 4 / channels
        if (frames.size < count * channels) frames = FloatArray(count * channels)
        val target = gain
        if (snap) { current = target; snap = false }
        var g = current
        for (f in 0 until count) {
            g += (target - g) * glideCoef
            for (c in 0 until channels) frames[f * channels + c] = input.float * g
        }
        current = g
        input.position(input.limit())
        limiter.process(frames, count)
        val output = replaceOutputBuffer(count * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count * channels) output.putFloat(frames[i])
        output.flip()
    }

    private fun coefficient(seconds: Double, rate: Int): Float =
        if (rate <= 0) 1f else (1.0 - exp(-1.0 / (seconds * rate))).toFloat()

    private companion object {
        /** -1 dBFS in 16-bit sample units. */
        const val LIMIT = 0.891f * 32767f
        /** Time constant giving about 95% of a gain change within 50 ms. */
        const val GLIDE_SECONDS = 0.0167
        /** Limiter release: the reduction is mostly gone after ~150 ms. */
        const val RELEASE_SECONDS = 0.05
        const val SETTLED = 1e-4f
    }
}
