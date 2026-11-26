package fr.spotifeur.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * How fast the music should run to stay with a Jam: 1 is as recorded. Kept within ±0.1%, where the pitch moves by less
 * than 2 cents and nobody hears it; the listen-together sync sets it, the output stage applies it.
 */
object RateTrim {
    const val MIN = 0.999f
    const val MAX = 1.001f

    @Volatile var rate: Float = 1f
        set(value) { field = value.coerceIn(MIN, MAX) }
}

/**
 * Plays float PCM at [RateTrim.rate]: a 32-tap windowed-sinc resampler (Kaiser, beta 9, 512 phases interpolated), so a
 * trim is a smooth change of length, not the stretching or stepping of a speed change. The rate glides to its target
 * over about 200 ms. [skewUs] is how far the media has run ahead of the audio written (input minus output), which the
 * output stage adds back to the reported position. The audio is held back by [latencyFrames], the kernel's half-width.
 */
@UnstableApi
class RateTrimProcessor : TailingAudioProcessor() {
    val latencyFrames: Int = HALF

    @Volatile var skewUs: Long = 0
        private set

    private var channels = 2
    private var sampleRate = 48000
    private var glide = 1.0
    private var current = 1.0
    /** Input frames not yet fully used, interleaved; [position] is the next output's place in it. */
    private var history = FloatArray(0)
    private var held = 0
    private var position = 0.0
    private var advanced = 0.0
    private var produced = 0L
    private val taps = DoubleArray(TAPS)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        glide = 1.0 - exp(-1.0 / (GLIDE_SECONDS * sampleRate))
        return inputAudioFormat
    }

    /** Jumps to the target rate at once: after a seek or a new song there is nothing playing to glide from. */
    fun glideNow() { current = RateTrim.rate.toDouble() }

    /** The frames held for the kernel, pushed out with silence behind them. */
    override fun writeTail() {
        if (held > HALF) queueInput(ByteBuffer.allocate(HALF * channels * 4).order(ByteOrder.LITTLE_ENDIAN))
    }

    override fun onFlushed() {
        held = 0; position = 0.0; advanced = 0.0; produced = 0; skewUs = 0
        // Silence on the left of the kernel; the first output is the first input frame, at index HALF.
        ensure(HALF); java.util.Arrays.fill(history, 0, HALF * channels, 0f); held = HALF
        position = HALF.toDouble()
        glideNow()
    }

    override fun onReset() { history = FloatArray(0); held = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = input.remaining() / 4 / channels
        if (frames == 0) return
        ensure(held + frames)
        for (i in 0 until frames * channels) history[held * channels + i] = input.float
        held += frames

        val target = RateTrim.rate.toDouble()
        // Every output frame needs HALF frames of input on its right.
        val maxOut = ((held - HALF - position) / minOf(current, target).coerceAtMost(1.0)).toInt() + 2
        val output = replaceOutputBuffer(maxOut.coerceAtLeast(0) * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        var count = 0
        while (position + HALF <= held - 1) {
            val base = floor(position).toInt()
            val fraction = position - base
            val phase = fraction * PHASES
            val p0 = phase.toInt().coerceAtMost(PHASES - 1)
            val mix = phase - p0
            val a = KERNEL[p0]; val b = KERNEL[p0 + 1]
            for (t in 0 until TAPS) taps[t] = a[t] + (b[t] - a[t]) * mix
            val first = (base - HALF + 1) * channels
            for (c in 0 until channels) {
                var sum = 0.0
                var at = first + c
                for (t in 0 until TAPS) { sum += taps[t] * history[at]; at += channels }
                output.putFloat(sum.toFloat())
            }
            count++
            current += (target - current) * glide
            position += current
            advanced += current
        }
        produced += count
        skewUs = ((advanced - produced) * 1_000_000 / sampleRate).toLong()
        output.flip()

        // Drop what no kernel will reach again.
        val drop = (floor(position).toInt() - HALF).coerceAtLeast(0)
        if (drop > 0) {
            System.arraycopy(history, drop * channels, history, 0, (held - drop) * channels)
            held -= drop; position -= drop
        }
    }

    private fun ensure(frames: Int) {
        if (history.size < frames * channels) history = history.copyOf(maxOf(frames * channels, history.size * 2))
    }

    private companion object {
        const val TAPS = 32
        const val HALF = TAPS / 2
        const val PHASES = 512
        const val GLIDE_SECONDS = 0.2 / 3 // about 95% of a change within 200 ms

        /**
         * KERNEL[p][t] is the weight of input frame (base - HALF + 1 + t) for an output at base + p / PHASES:
         * sinc(t - (HALF - 1) - p / PHASES) under a Kaiser window, one extra phase so interpolation never runs off.
         */
        val KERNEL: Array<DoubleArray> = Array(PHASES + 1) { p ->
            val frac = p.toDouble() / PHASES
            val taps = DoubleArray(TAPS) { t ->
                val x = t - (HALF - 1) - frac
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                sinc * kaiser(x / HALF, 9.0)
            }
            val sum = taps.sum()
            DoubleArray(TAPS) { taps[it] / sum }
        }

        fun kaiser(x: Double, beta: Double): Double {
            if (x <= -1.0 || x >= 1.0) return 0.0
            return bessel0(beta * kotlin.math.sqrt(1 - x * x)) / bessel0(beta)
        }

        fun bessel0(x: Double): Double {
            var sum = 1.0; var term = 1.0; var k = 1
            while (term > 1e-12 * sum) { term *= (x / (2 * k)) * (x / (2 * k)); sum += term; k++ }
            return sum
        }
    }
}
