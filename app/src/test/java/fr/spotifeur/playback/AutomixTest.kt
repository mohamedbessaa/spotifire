package fr.spotifeur.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import fr.spotifeur.playback.dsp.EnergyMeter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AutomixTest {
    private fun feed(meter: EnergyMeter, seconds: Int, amp: Double) {
        val rate = 8000
        repeat(seconds * 10) { chunk ->
            val buffer = ByteBuffer.allocate(rate / 10 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(rate / 10) { i -> buffer.putShort((amp * 32767 * sin(2 * PI * 440 * (chunk * rate / 10 + i) / rate)).toInt().toShort()) }
            buffer.flip(); meter.queueInput(buffer); meter.output
        }
    }

    @Test
    fun theOutroIsFoundWhereTheSongWindsDown() {
        val meter = EnergyMeter().apply { configure(AudioFormat(8000, 1, C.ENCODING_PCM_16BIT)); flush() }
        feed(meter, 15, 0.5)
        assertFalse("a steady song has no outro yet", meter.outro)
        feed(meter, 2, 0.05)
        assertTrue("20 dB down is an outro", meter.outro)
    }

    @Test
    fun aQuietIntroIsNotAnOutro() {
        val meter = EnergyMeter().apply { configure(AudioFormat(8000, 1, C.ENCODING_PCM_16BIT)); flush() }
        feed(meter, 3, 0.02)
        assertFalse(meter.outro)
    }
}
