package fr.spotifeur.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import fr.spotifeur.playback.dsp.AutoEq
import fr.spotifeur.playback.dsp.EqBand
import fr.spotifeur.playback.dsp.EqCurve
import fr.spotifeur.playback.dsp.EqProcessor
import fr.spotifeur.playback.dsp.SpatialProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SoundDspTest {
    private fun sine(freq: Double, rate: Int, seconds: Double, amp: Double, channels: Int) =
        ShortArray((rate * seconds).toInt() * channels) { i -> (amp * 32767 * sin(2 * PI * freq * (i / channels) / rate)).toInt().toShort() }

    private fun run(processor: androidx.media3.common.audio.BaseAudioProcessor, input: ShortArray, rate: Int, channels: Int): ShortArray {
        processor.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)); processor.flush()
        val buffer = ByteBuffer.allocate(input.size * 2).order(ByteOrder.LITTLE_ENDIAN); input.forEach { buffer.putShort(it) }; buffer.flip()
        processor.queueInput(buffer)
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(out.remaining() / 2) { out.short }
    }

    private fun rmsDb(samples: ShortArray, from: Int) = samples.drop(from).let { s -> 20 * log10(sqrt(s.sumOf { it.toDouble() * it } / s.size) / 32768) }

    @Test
    fun aPeakBoostsItsFrequencyByItsGain() {
        val eq = EqProcessor().apply { curve = EqCurve(0.0, listOf(EqBand(EqBand.Type.PEAK, 1000.0, 6.0, 1.0))) }
        val input = sine(1000.0, 48000, 0.5, 0.2, 1)
        val gain = rmsDb(run(eq, input, 48000, 1), 4800) - rmsDb(input, 4800)
        assertEquals(6.0, gain, 0.3)
    }

    @Test
    fun aFlatCurveLeavesTheSignalUntouched() {
        val input = sine(440.0, 44100, 0.1, 0.5, 2)
        assertEquals(input.toList(), run(EqProcessor(), input, 44100, 2).toList())
        assertEquals(input.toList(), run(SpatialProcessor(), input, 44100, 2).toList())
    }

    @Test
    fun spatialWidensOnlyTheSideAndKeepsMonoCentered() {
        // A mono (centered) source has no side: widening must not split it between the ears.
        val mono = sine(440.0, 44100, 0.2, 0.3, 2)
        val out = run(SpatialProcessor().apply { amount = 1f }, mono, 44100, 2)
        val skew = (2000 until out.size step 2).maxOf { kotlin.math.abs(out[it] - out[it + 1]) }
        assertTrue("left and right stay equal for a centered voice, skew=$skew", skew <= 2)
    }

    @Test
    fun autoEqProfileAndIndexAreRead() {
        val curve = AutoEq.parseProfile("Preamp: -5.6 dB\nFilter 1: ON LSC Fc 105 Hz Gain 2.2 dB Q 0.70\nFilter 2: ON PK Fc 173 Hz Gain -1.0 dB Q 0.18\nFilter 6: ON HSC Fc 10000 Hz Gain 5.6 dB Q 0.70")
        assertEquals(-5.6, curve.preampDb, 1e-9)
        assertEquals(listOf(EqBand.Type.LOW_SHELF, EqBand.Type.PEAK, EqBand.Type.HIGH_SHELF), curve.bands.map { it.type })
        val index = AutoEq.parseIndex("""
            - [Samsung Galaxy Buds](./crinacle/711%20in-ear/Samsung%20Galaxy%20Buds) by crinacle on 711
            - [Samsung Galaxy Buds](./oratory1990/in-ear/Samsung%20Galaxy%20Buds) by oratory1990
            - [Samsung Galaxy Buds2 Pro](./oratory1990/in-ear/Samsung%20Galaxy%20Buds2%20Pro) by oratory1990
            - [Sony WH-1000XM4](./oratory1990/over-ear/Sony%20WH-1000XM4) by oratory1990
        """.trimIndent())
        assertEquals("oratory1990/in-ear/Samsung%20Galaxy%20Buds2%20Pro", AutoEq.match("Galaxy Buds2 Pro (4A2B)", index)?.path)
        assertEquals("oratory1990", AutoEq.match("Galaxy Buds", index)?.source)
        assertEquals("Sony WH-1000XM4", AutoEq.match("WH-1000XM4", index)?.name)
        assertNull(AutoEq.match("Car stereo", index))
    }
}
