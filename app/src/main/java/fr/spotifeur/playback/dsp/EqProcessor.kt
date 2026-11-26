package fr.spotifeur.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One filter of an equalizer, in the terms AutoEq and every parametric EQ use. */
data class EqBand(val type: Type, val frequency: Double, val gainDb: Double, val q: Double) {
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF }
}

/** A whole equalization: a preamp and its bands. */
data class EqCurve(val preampDb: Double, val bands: List<EqBand>) {
    val isFlat: Boolean get() = bands.all { it.gainDb == 0.0 } && preampDb == 0.0
    operator fun plus(other: EqCurve) = EqCurve(preampDb + other.preampDb, bands + other.bands)
    companion object { val FLAT = EqCurve(0.0, emptyList()) }
}

/** Biquad coefficients from the RBJ Audio EQ Cookbook, normalised by a0. */
internal class Biquad(band: EqBand, rate: Int) {
    val b0: Double; val b1: Double; val b2: Double; val a1: Double; val a2: Double
    init {
        val a = 10.0.pow(band.gainDb / 40)
        val w = 2 * PI * band.frequency.coerceIn(10.0, rate * 0.45) / rate
        val alpha = sin(w) / (2 * band.q.coerceAtLeast(0.05))
        val c = cos(w)
        val raw = when (band.type) {
            EqBand.Type.PEAK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            EqBand.Type.LOW_SHELF -> { val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
                    (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s) }
            EqBand.Type.HIGH_SHELF -> { val s = 2 * sqrt(a) * alpha
                doubleArrayOf(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                    (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s) }
        }
        b0 = raw[0] / raw[3]; b1 = raw[1] / raw[3]; b2 = raw[2] / raw[3]; a1 = raw[4] / raw[3]; a2 = raw[5] / raw[3]
    }
}

/**
 * The in-app equalizer: the user's tone and the headphone correction, as biquads on float or 16-bit PCM inside the player's
 * own chain, so no OEM effect can swallow it. [curve] is swapped from the main thread and picked up at the next
 * buffer; the filter state resets then, which is inaudible at a track change or a settings tap.
 */
@UnstableApi
class EqProcessor : BaseAudioProcessor() {
    @Volatile var curve: EqCurve = EqCurve.FLAT
    private var applied: EqCurve? = null
    private var filters: Array<Biquad> = emptyArray()
    private var state = DoubleArray(0)
    private var preamp = 1.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (Pcm.supported(inputAudioFormat) && inputAudioFormat.channelCount in 1..2) inputAudioFormat
        else AudioFormat.NOT_SET

    override fun onFlush() { applied = null }

    private fun prepare(target: EqCurve) {
        val rate = inputAudioFormat.sampleRate
        filters = target.bands.filter { it.gainDb != 0.0 }.map { Biquad(it, rate) }.toTypedArray()
        state = DoubleArray(filters.size * inputAudioFormat.channelCount * 4)
        preamp = 10.0.pow(target.preampDb / 20)
        applied = target
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val target = curve
        val output = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (target.isFlat) { output.put(input).flip(); return }
        if (applied !== target) prepare(target)
        val channels = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val step = if (float) 4 else 2
        var channel = 0
        while (input.remaining() >= step) {
            var x = (if (float) input.float.toDouble() else input.short / 32768.0) * preamp
            for (i in filters.indices) {
                val f = filters[i]; val o = (i * channels + channel) * 4
                val y = f.b0 * x + f.b1 * state[o] + f.b2 * state[o + 1] - f.a1 * state[o + 2] - f.a2 * state[o + 3]
                state[o + 1] = state[o]; state[o] = x; state[o + 3] = state[o + 2]; state[o + 2] = y
                x = y
            }
            // Float keeps what goes over full scale: the limiter at the end of the chain brings it back.
            if (float) output.putFloat(x.toFloat()) else output.putShort((x * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort())
            channel = (channel + 1) % channels
        }
        output.flip()
    }
}

/**
 * "Dimensional": widens the stereo image (mid/side) and mixes in a short, low-passed cross-feed, the trick consumer
 * virtual-surround effects use. Adapted from BitChord `SpatialAudioProcessor.kt` (GPLv3), which runs it in the player
 * because the platform Virtualizer was inaudible behind the OEM chain. Stereo float or 16-bit; anything else passes through.
 */
@UnstableApi
class SpatialProcessor : BaseAudioProcessor() {
    /** 0 = off, 1 = BitChord's width. */
    @Volatile var amount: Float = 0f
    private var delayLeft = FloatArray(0)
    private var delayRight = FloatArray(0)
    private var index = 0
    private var lowLeft = 0f
    private var lowRight = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!Pcm.supported(inputAudioFormat) || inputAudioFormat.channelCount != 2) return AudioFormat.NOT_SET
        val samples = (inputAudioFormat.sampleRate * DELAY_MS / 1000).coerceAtLeast(1)
        delayLeft = FloatArray(samples); delayRight = FloatArray(samples)
        return inputAudioFormat
    }

    override fun onFlush() { delayLeft.fill(0f); delayRight.fill(0f); index = 0; lowLeft = 0f; lowRight = 0f }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val k = amount
        if (k <= 0f || delayLeft.isEmpty()) { output.put(input).flip(); return }
        val width = 1f + (WIDTH - 1f) * k
        val feed = CROSSFEED * k
        val makeup = 1f - (1f - MAKEUP) * k
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frame = if (float) 8 else 4
        while (input.remaining() >= frame) {
            val l = if (float) input.float else input.short / 32768f
            val r = if (float) input.float else input.short / 32768f
            val mid = (l + r) * 0.5f; val side = (l - r) * 0.5f * width
            lowLeft += LOWPASS * (delayRight[index] - lowLeft)
            lowRight += LOWPASS * (delayLeft[index] - lowRight)
            delayLeft[index] = l; delayRight[index] = r
            index = (index + 1) % delayLeft.size
            val left = ((mid + side) + lowLeft * feed) * makeup
            val right = ((mid - side) + lowRight * feed) * makeup
            if (float) { output.putFloat(left); output.putFloat(right) }
            else {
                output.putShort((left * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
                output.putShort((right * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
            }
        }
        output.put(input)
        output.flip()
    }

    private companion object {
        const val DELAY_MS = 15
        const val WIDTH = 2.5f
        const val CROSSFEED = 0.2f
        const val LOWPASS = 0.3f
        const val MAKEUP = 0.82f
    }
}

/** The PCM encodings the effects work in: 16-bit, and 32-bit float (the high-precision path). */
internal object Pcm {
    fun supported(format: AudioFormat) = format.encoding == C.ENCODING_PCM_16BIT || format.encoding == C.ENCODING_PCM_FLOAT
}
