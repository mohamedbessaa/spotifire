package fr.spotifeur.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import fr.spotifeur.data.preferences.AppSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {
    private fun gain(loudness: Float, peak: Float, level: String) =
        TrackLoudness.spotifyGainDb(loudness, peak, TrackLoudness.targetLufs(level)!!, limitByPeak = level != TrackLoudness.LOUD)!!

    @Test
    fun gainFollowsTheLevelAndTheTruePeak() {
        assertEquals(-6f, gain(-8f, -0.5f, "normal"), 0.001f)
        assertEquals(-15f, gain(-8f, -0.5f, "quiet"), 0.001f)
        // -20 LUFS needs +6 dB at normal, but its -3 dBTP peak allows +2 dB; loud ignores the peak.
        assertEquals(2f, gain(-20f, -3f, "normal"), 0.001f)
        assertEquals(9f, gain(-20f, -3f, "loud"), 0.001f)
        assertEquals(12f, gain(-30f, Float.NaN, "loud"), 0.001f)
    }

    @Test
    fun youTubeOffsetAboveTheReferenceIsTurnedDown() {
        // +3 dB above YouTube's -14 LUFS reference is a -11 LUFS track.
        assertEquals(-3f, TrackLoudness.youTubeGainDb(3f, -14f), 0.001f)
        assertEquals(-12f, TrackLoudness.youTubeGainDb(3f, -23f), 0.001f)
        assertEquals(4f, TrackLoudness.youTubeGainDb(-4f, -14f), 0.001f)
    }

    @Test
    fun oldBooleanSettingMapsToALevel() {
        assertEquals("normal", AppSettings.fromJson(JSONObject("{\"audioNormalization\":true}")).normalizationLevel)
        assertEquals("off", AppSettings.fromJson(JSONObject("{\"audioNormalization\":false}")).normalizationLevel)
        assertEquals("normal", AppSettings.fromJson(JSONObject("{}")).normalizationLevel)
    }

    private fun run(gain: Float, samples: ShortArray): ShortArray {
        val processor = LoudnessProcessor()
        processor.configure(AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.gain = gain
        val input = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun limiterKeepsAFullScaleSineUnderTheCeiling() {
        val sine = ShortArray(44100) { (32767 * sin(2 * PI * 1000 * it / 44100)).toInt().toShort() }
        val out = run(Math.pow(10.0, 12 / 20.0).toFloat(), sine)
        val skip = 44100 * 5 / 1000
        val peak = out.drop(skip).maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak <= 0.891 * 32767)
        assertTrue("signal must not vanish", peak > 20000)
    }

    @Test
    fun unityGainLeavesSamplesUnchanged() {
        val samples = ShortArray(1000) { (20000 * sin(2 * PI * 440 * it / 44100)).toInt().toShort() }
        assertEquals(samples.toList(), run(1f, samples).toList())
    }
}
