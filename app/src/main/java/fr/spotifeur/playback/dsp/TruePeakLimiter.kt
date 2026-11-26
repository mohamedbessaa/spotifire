package fr.spotifeur.playback.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A lookahead limiter on the true peak: each frame's peak is estimated at 4x the sample rate (a 64-tap polyphase
 * windowed-sinc, 16 taps per phase), so peaks that fall between samples count too. The gain is the minimum the next
 * 2 ms need, smoothed by a box filter as long as the lookahead, so it is already down when a peak arrives and never
 * steps; it comes back with a ~150 ms release. Audio is delayed by [latencyFrames].
 *
 * Processes interleaved float frames in place: what comes out is the input from [latencyFrames] frames earlier.
 */
class TruePeakLimiter(sampleRate: Int, private val channels: Int, ceilingDb: Float = -0.3f) {
    private val ceiling = 10.0.pow(ceilingDb / 20.0)
    private val lookahead = (sampleRate * LOOKAHEAD_SECONDS).toInt().coerceAtLeast(1)
    val latencyFrames: Int = lookahead + FIR_DELAY
    private val release = 1.0 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))

    // Oversampling history, per channel, newest last (ring).
    // Written twice (at i and i + TAPS_PER_PHASE), so the newest-first window is one contiguous run, no modulo.
    private val history = Array(channels) { DoubleArray(TAPS_PER_PHASE * 2) }
    private var historyAt = 0
    // Delay line of the audio itself.
    private val delay = FloatArray(latencyFrames * channels)
    private var delayAt = 0
    // Sliding minimum of the required gain over lookahead + 1 frames (monotonic deque of (frame, gain)).
    private val minFrames = LongArray(lookahead + 2)
    private val minGains = DoubleArray(lookahead + 2)
    private var minHead = 0
    private var minSize = 0
    // Box average of the sliding minimum over lookahead frames.
    private val box = DoubleArray(lookahead) { 1.0 }
    private var boxAt = 0
    private var boxSum = lookahead.toDouble()
    private var boxBelow = 0
    private var gain = 1.0
    private var frame = 0L

    fun process(interleaved: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val base = f * channels
            // 1. True peak of the frame FIR_DELAY frames back, from the oversampled estimate and the sample itself.
            var peak = 0.0
            for (c in 0 until channels) {
                val h = history[c]
                val x = interleaved[base + c].toDouble()
                h[historyAt] = x; h[historyAt + TAPS_PER_PHASE] = x
                val newest = historyAt + TAPS_PER_PHASE
                for (p in 0 until 4) {
                    val taps = PHASES[p]
                    var y = 0.0
                    for (j in 0 until TAPS_PER_PHASE) y += taps[j] * h[newest - j]
                    peak = maxOf(peak, abs(y))
                }
                peak = maxOf(peak, abs(h[newest - FIR_DELAY]))
            }
            historyAt = (historyAt + 1) % TAPS_PER_PHASE
            val required = if (peak > ceiling) ceiling / peak else 1.0

            // 2. Minimum over the last lookahead + 1 requirements.
            while (minSize > 0 && minGains[(minHead + minSize - 1) % minGains.size] >= required) minSize--
            minFrames[(minHead + minSize) % minFrames.size] = frame; minGains[(minHead + minSize) % minGains.size] = required; minSize++
            while (minFrames[minHead] <= frame - lookahead - 1) { minHead = (minHead + 1) % minFrames.size; minSize-- }
            val floor = minGains[minHead]

            // 3. Box average of that minimum: reaches it exactly when the peak's frame comes out.
            val leaving = box[boxAt]
            if (leaving < 1.0) boxBelow--
            if (floor < 1.0) boxBelow++
            boxSum += floor - leaving
            box[boxAt] = floor
            boxAt = (boxAt + 1) % lookahead
            val target = if (boxBelow == 0) 1.0 else boxSum / lookahead

            // 4. Attack follows the target down at once; release eases back up.
            gain = if (target < gain) target else gain + (target - gain) * release
            if (1.0 - gain < 1e-7) gain = 1.0

            // 5. Out goes the delayed frame, scaled; in goes the new one.
            val d = delayAt * channels
            for (c in 0 until channels) {
                val incoming = interleaved[base + c]
                interleaved[base + c] = if (gain == 1.0) delay[d + c] else (delay[d + c] * gain).toFloat()
                delay[d + c] = incoming
            }
            delayAt = (delayAt + 1) % latencyFrames
            frame++
        }
    }

    /** The [latencyFrames] frames still held, pushed out with silence behind them (end of stream). */
    fun drain(): FloatArray = FloatArray(latencyFrames * channels).also { process(it, latencyFrames) }

    fun reset() {
        history.forEach { it.fill(0.0) }; historyAt = 0
        delay.fill(0f); delayAt = 0
        minHead = 0; minSize = 0
        box.fill(1.0); boxAt = 0; boxSum = lookahead.toDouble(); boxBelow = 0
        gain = 1.0; frame = 0
    }

    private companion object {
        const val LOOKAHEAD_SECONDS = 0.002
        const val RELEASE_SECONDS = 0.05
        const val TAPS_PER_PHASE = 16
        /** Group delay of the 64-tap interpolator, in input frames. */
        const val FIR_DELAY = 8

        /** 4x interpolator: Blackman-windowed sinc cut at the original Nyquist, split into four phases of 16 taps. */
        val PHASES: Array<DoubleArray> = run {
            val n = 4 * TAPS_PER_PHASE
            val centre = (n - 1) / 2.0
            val h = DoubleArray(n) { k ->
                val t = (k - centre) / 4.0
                val sinc = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)
                val w = 0.42 - 0.5 * cos(2 * PI * k / (n - 1)) + 0.08 * cos(4 * PI * k / (n - 1))
                sinc * w
            }
            Array(4) { p -> DoubleArray(TAPS_PER_PHASE) { j -> h[p + 4 * j] }.let { taps -> val s = taps.sum(); DoubleArray(taps.size) { taps[it] / s } } }
        }
    }
}
