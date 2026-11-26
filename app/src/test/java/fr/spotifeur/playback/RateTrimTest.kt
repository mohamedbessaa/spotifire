package fr.spotifeur.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import fr.spotifeur.playback.dsp.RateTrim
import fr.spotifeur.playback.dsp.RateTrimProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The Jam's rate trim: exact in length, smooth while it moves, and accounted for in the reported position. */
class RateTrimTest {
    private val rate = 48000

    @After fun backToOne() { RateTrim.rate = 1f }

    private fun sine(frames: Int, freq: Double = 1000.0, amp: Double = 0.5) =
        FloatArray(frames * 2) { i -> (amp * sin(2 * PI * freq * (i / 2) / rate)).toFloat() }

    /** Feeds [input] in 10 ms blocks, calling [between] before each block, and returns the left channel out. */
    private fun run(processor: RateTrimProcessor, input: FloatArray, between: (Int) -> Unit = {}): FloatArray {
        processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)); processor.flush()
        val out = ArrayList<Float>(input.size / 2)
        val block = 480 * 2
        var at = 0
        while (at < input.size) {
            between(at / 2)
            val n = minOf(block, input.size - at)
            val b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN); for (i in at until at + n) b.putFloat(input[i]); b.flip()
            processor.queueInput(b)
            val o = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            while (o.remaining() >= 8) { out.add(o.float); o.float }
            at += n
        }
        return out.toFloatArray()
    }

    @Test
    fun trimChangesLengthExactly() {
        RateTrim.rate = 1.001f
        val processor = RateTrimProcessor().apply { glideNow() }
        val out = run(processor, sine(480_000))
        // The resampler holds its kernel's half-width back; everything else comes out shortened by the rate.
        assertEquals(480_000 / 1.001, (out.size + processor.latencyFrames).toDouble(), 2.0)
    }

    @Test
    fun skewTracksTheTrim() {
        RateTrim.rate = 1.001f
        val processor = RateTrimProcessor().apply { glideNow() }
        val out = run(processor, sine(480_000))
        val expectedUs = (480_000 - processor.latencyFrames - out.size) * 1_000_000.0 / rate
        assertEquals(expectedUs, processor.skewUs.toDouble(), 50.0)
        assertEquals(10_000_000 * (1 - 1 / 1.001), processor.skewUs.toDouble(), 50.0)
    }

    @Test
    fun noClicksWhileGliding() {
        // A click is a jump between neighbouring samples; a clean sine's second difference stays under A * w^2.
        val steps = listOf(1.0f, 1.001f, 0.999f, 1.001f, 1.0f)
        val input = sine(48_000 * 5)
        val out = run(RateTrimProcessor(), input) { frame -> RateTrim.rate = steps[(frame / 48_000).coerceAtMost(steps.size - 1)] }
        val w = 2 * PI * 1000 * 1.0011 / rate
        val bound = 0.5 * w * w * 1.05
        var worst = 0.0
        for (i in 100 until out.size - 1) worst = maxOf(worst, abs(out[i + 1] - 2 * out[i] + out[i - 1]).toDouble())
        assertTrue("worst second difference $worst over bound $bound", worst <= bound)
    }

    @Test
    fun trimmedToneStaysClean() {
        // After the glide settles, the output is a pure tone at 1000 x rate: what is left after fitting it is noise.
        RateTrim.rate = 1.001f
        val out = run(RateTrimProcessor(), sine(48_000 * 2))
        val w = 2 * PI * 1000 * 1.001 / rate
        val from = 48_000; val n = 9600
        var ss = 0.0; var sc = 0.0; var cc = 0.0; var ys = 0.0; var yc = 0.0
        for (k in 0 until n) { val s = sin(w * k); val c = cos(w * k); val y = out[from + k].toDouble()
            ss += s * s; sc += s * c; cc += c * c; ys += y * s; yc += y * c }
        val det = ss * cc - sc * sc
        val a = (ys * cc - yc * sc) / det; val b = (yc * ss - ys * sc) / det
        var residual = 0.0; var signal = 0.0
        for (k in 0 until n) { val y = out[from + k].toDouble(); val fit = a * sin(w * k) + b * cos(w * k)
            residual += (y - fit) * (y - fit); signal += y * y }
        val db = 10 * log10(residual / signal)
        assertTrue("residual $db dB", db < -80)
        assertEquals(0.5, sqrt(a * a + b * b), 0.005)
    }

    @Test
    fun endOfStreamLetsTheTailOutOnce() {
        // The frames held for the kernel come out at the end of a stream, once (Media3 repeats queueEndOfStream).
        val pipeline = androidx.media3.common.audio.AudioProcessingPipeline(com.google.common.collect.ImmutableList.of(RateTrimProcessor()))
        pipeline.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)); pipeline.flush()
        val input = sine(4800)
        pipeline.queueInput(ByteBuffer.allocate(input.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { input.forEach { putFloat(it) }; flip() })
        pipeline.queueEndOfStream()
        var frames = 0L; var passes = 0
        while (!pipeline.isEnded && passes < 10_000) { val o = pipeline.output; frames += o.remaining() / 8; o.position(o.limit()); passes++ }
        assertTrue("the drain ends", pipeline.isEnded)
        assertEquals("every frame, none twice", 4800L, frames)
    }

    @Test
    fun rateIsClamped() {
        RateTrim.rate = 1.05f
        assertEquals(1.001f, RateTrim.rate, 0f)
        RateTrim.rate = 0.5f
        assertEquals(0.999f, RateTrim.rate, 0f)
    }
}
