package fr.spotifeur.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import fr.spotifeur.playback.dsp.EqBand
import fr.spotifeur.playback.dsp.EqCurve
import fr.spotifeur.playback.dsp.EqProcessor
import fr.spotifeur.playback.dsp.SpatialProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The effects in 32-bit float: transparent when flat, never over -0.3 dBTP, and the same sound as the 16-bit path. */
class FloatDspTest {
    private val rate = 48000

    private fun tone(seconds: Double, channels: Int, vararg parts: Pair<Double, Double>) =
        FloatArray((rate * seconds).toInt() * channels) { i -> parts.sumOf { (f, a) -> a * sin(2 * PI * f * (i / channels) / rate) }.toFloat() }

    /** Runs [processors] in order on float PCM, flushing each, and returns everything they output (end of stream included). */
    private fun runFloat(input: FloatArray, channels: Int, vararg processors: BaseAudioProcessor): FloatArray {
        var format = AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT)
        processors.forEach { format = it.configure(format).takeIf { f -> f != AudioFormat.NOT_SET } ?: format; it.flush() }
        var data = ByteBuffer.allocate(input.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { input.forEach { putFloat(it) }; flip() }
        for (p in processors) {
            val collected = java.io.ByteArrayOutputStream()
            fun drain() { val out = p.output; val bytes = ByteArray(out.remaining()); out.get(bytes); collected.write(bytes) }
            if (p.isActive) { p.queueInput(data); drain(); p.queueEndOfStream(); drain() } else { val b = ByteArray(data.remaining()); data.get(b); collected.write(b) }
            data = ByteBuffer.wrap(collected.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        }
        return FloatArray(data.remaining() / 4) { data.float }
    }

    private fun runShort(input: FloatArray, channels: Int, vararg processors: BaseAudioProcessor): FloatArray {
        processors.forEach { it.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)); it.flush() }
        var data = ByteBuffer.allocate(input.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { input.forEach { putShort((it * 32767).toInt().toShort()) }; flip() }
        for (p in processors) {
            p.queueInput(data); val out = p.output; val bytes = ByteArray(out.remaining()); out.get(bytes)
            data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        return FloatArray(data.remaining() / 2) { data.short / 32768f }
    }

    private fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size) =
        20 * log10(sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from)).coerceAtLeast(1e-12))

    /** Peak with 4x linear interpolation between samples, per channel. */
    private fun interpolatedPeakDb(x: FloatArray, channels: Int): Double {
        var peak = 0.0
        for (c in 0 until channels) for (i in c until x.size - channels step channels) for (k in 0..3) {
            val v = x[i] + (x[i + channels] - x[i]) * k / 4.0
            peak = max(peak, abs(v))
        }
        return 20 * log10(peak)
    }

    @Test
    fun floatFlatChainIsTransparent() {
        val input = tone(1.0, 2, 1000.0 to 0.5)
        val output = runFloat(input, 2, EqProcessor(), SpatialProcessor(), LoudnessProcessor())
        val delay = (output.size - input.size).let { if (it > 0) it else 0 } // tail emitted at end of stream
        val lag = (0..400).first { lag -> (0 until 2000).all { i -> abs(output[i + lag * 2] - input[i]) < 1e-6f } || lag == 400 }
        assertTrue("found the chain's latency", lag < 400)
        val diff = FloatArray(input.size - lag * 2 - 2) { i -> output[i + lag * 2] - input[i] }
        assertTrue("residual ${rmsDb(diff)} dBFS (delay=$delay)", rmsDb(diff) < -120)
    }

    @Test
    fun limiterHoldsTruePeakUnderBoost() {
        val random = java.util.Random(7)
        // Pink-ish noise (Paul Kellet's filter), normalised to full scale.
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        val noise = FloatArray(rate * 10 * 2) { i ->
            if (i % 2 == 1) 0f else { val w = random.nextGaussian(); b0 = 0.99765 * b0 + w * 0.0990460; b1 = 0.96300 * b1 + w * 0.2965164
                b2 = 0.57000 * b2 + w * 1.0526913; (b0 + b1 + b2 + w * 0.1848).toFloat() }
        }
        for (i in 1 until noise.size step 2) noise[i] = noise[i - 1]
        val scale = 1f / noise.maxOf { abs(it) }
        for (i in noise.indices) noise[i] *= scale
        val eq = EqProcessor().apply { curve = EqCurve(0.0, listOf(EqBand(EqBand.Type.PEAK, 100.0, 12.0, 0.7))) }
        val output = runFloat(noise, 2, eq, LoudnessProcessor())
        val peak = interpolatedPeakDb(output, 2)
        assertTrue("true peak $peak dBTP", peak <= -0.3 + 0.1)
        assertTrue("the boost is still there, not just turned down: ${rmsDb(output)} vs ${rmsDb(noise)}", rmsDb(output) > rmsDb(noise))
    }

    @Test
    fun floatMatches16Bit() {
        val input = tone(1.0, 2, 440.0 to 0.1, 3000.0 to 0.05)
        fun eq() = EqProcessor().apply { curve = EqCurve(-2.0, listOf(EqBand(EqBand.Type.PEAK, 3000.0, 4.0, 1.0), EqBand(EqBand.Type.LOW_SHELF, 120.0, 3.0, 0.7))) }
        fun spatial() = SpatialProcessor().apply { amount = 1f }
        val viaShort = runShort(input, 2, eq(), spatial())
        val viaFloat = runFloat(input, 2, eq(), spatial())
        assertEquals(rmsDb(viaShort, 4800), rmsDb(viaFloat, 4800, viaShort.size), 0.01)
    }

    @Test
    fun twentyFourBitDetailSurvives() {
        val input = tone(1.0, 2, 1000.0 to 1e-5) // -100 dBFS: below 16-bit's last bit
        val output = runFloat(input, 2, EqProcessor(), SpatialProcessor(), LoudnessProcessor())
        assertEquals(rmsDb(input, 4800, input.size - 4800), rmsDb(output, 4800, input.size - 4800), 0.1)
    }
}
