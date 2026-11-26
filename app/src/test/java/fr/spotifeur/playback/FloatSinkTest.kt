package fr.spotifeur.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import fr.spotifeur.playback.dsp.FloatSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatSinkTest {
    /** An output that records the format it is given and the bytes it takes; [acceptAtMost] limits each call. */
    private class RecordingSink : AudioSink {
        var format: Format? = null
        val bytes = ByteArrayOutputStream()
        var acceptAtMost = Int.MAX_VALUE
        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) { format = audioSinkConfig.format }
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val n = minOf(buffer.remaining(), acceptAtMost)
            val chunk = ByteArray(n); buffer.get(chunk); bytes.write(chunk)
            return !buffer.hasRemaining()
        }
        override fun setListener(listener: AudioSink.Listener) {}
        override fun supportsFormat(format: Format) = true
        override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getCurrentPositionUs(sourceEnded: Boolean) = 0L
        override fun play() {}
        override fun handleDiscontinuity() {}
        override fun playToEndOfStream() {}
        override fun isEnded() = true
        override fun hasPendingData() = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
        override fun getSkipSilenceEnabled() = false
        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
        override fun getAudioAttributes(): AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) {}
        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
        override fun getAudioTrackBufferSizeUs() = 0L
        override fun enableTunnelingV21() {}
        override fun disableTunneling() {}
        override fun setVolume(volume: Float) {}
        override fun pause() {}
        override fun flush() {}
        override fun reset() {}
    }

    private fun pcm(encoding: Int, rate: Int, channels: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding).setSampleRate(rate).setChannelCount(channels).build()

    /** 24-bit little-endian frames of a ramp, [frames] long, stereo. */
    private fun ramp24(frames: Int): ByteBuffer = ByteBuffer.allocate(frames * 2 * 3).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (i in 0 until frames * 2) { val v = (i * 997) % 8_000_000 - 4_000_000; put(v.toByte()); put((v shr 8).toByte()); put((v shr 16).toByte()) }
        flip()
    }

    @Test
    fun hiResFlacGoesFloat() {
        val inner = RecordingSink()
        val sink = FloatSink(inner, emptyList()) { false }
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(pcm(C.ENCODING_PCM_24BIT, 96000, 2)))
        sink.configure(AudioSink.AudioSinkConfig.Builder(pcm(C.ENCODING_PCM_24BIT, 96000, 2)).build())
        assertEquals(C.ENCODING_PCM_FLOAT, inner.format!!.pcmEncoding)
        assertEquals(96000, inner.format!!.sampleRate)
        assertTrue(sink.handleBuffer(ramp24(1000), 0, 1))
        assertEquals("every frame arrives, as 2 x 4-byte floats", 1000 * 2 * 4, inner.bytes.size())
        val first = ByteBuffer.wrap(inner.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(-4_000_000 / 8_388_608f, first, 1e-6f)
    }

    @Test
    fun partialAcceptIsRetried() {
        val inner = RecordingSink().apply { acceptAtMost = 1000 * 2 * 4 / 2 }
        val sink = FloatSink(inner, emptyList()) { false }
        sink.configure(AudioSink.AudioSinkConfig.Builder(pcm(C.ENCODING_PCM_24BIT, 48000, 2)).build())
        val buffer = ramp24(1000)
        assertFalse("half taken: not handled yet", sink.handleBuffer(buffer, 0, 1))
        assertTrue(sink.hasPendingData())
        inner.acceptAtMost = Int.MAX_VALUE
        assertTrue("the rest goes on the retry", sink.handleBuffer(buffer, 0, 1))
        assertFalse(buffer.hasRemaining())
        val all = ByteBuffer.wrap(inner.bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("each byte exactly once", 1000 * 2 * 4, all.remaining())
        val reference = run {
            val r = RecordingSink(); val f = FloatSink(r, emptyList()) { false }
            f.configure(AudioSink.AudioSinkConfig.Builder(pcm(C.ENCODING_PCM_24BIT, 48000, 2)).build()); f.handleBuffer(ramp24(1000), 0, 1); r.bytes.toByteArray()
        }
        assertArrayEquals("in order", reference, inner.bytes.toByteArray())
    }

    @Test
    fun skipSilenceHandsSixteenBit() {
        val inner = RecordingSink()
        val sink = FloatSink(inner, emptyList()) { true }
        sink.configure(AudioSink.AudioSinkConfig.Builder(pcm(C.ENCODING_PCM_24BIT, 48000, 2)).build())
        assertEquals(C.ENCODING_PCM_16BIT, inner.format!!.pcmEncoding)
        assertTrue(sink.handleBuffer(ramp24(500), 0, 1))
        assertEquals(500 * 2 * 2, inner.bytes.size())
    }

    /** 16-bit stereo sine blocks, as a decoder hands them over. */
    private fun sine16(frames: Int, from: Int = 0): ByteBuffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (i in from until from + frames) { val v = (12000 * kotlin.math.sin(2 * Math.PI * 440 * i / 48000)).toInt().toShort(); putShort(v); putShort(v) }
        flip()
    }

    private fun rmsDbOfFloats(bytes: ByteArray, skipFrames: Int): Double {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(minOf(skipFrames * 8, b.limit()))
        var sum = 0.0; var n = 0
        while (b.remaining() >= 4) { val v = b.float.toDouble(); sum += v * v; n++ }
        return if (n == 0) -999.0 else 10 * kotlin.math.log10((sum / n).coerceAtLeast(1e-30))
    }

    @Test
    fun soundSurvivesAFlush() {
        // A new queue flushes the sink without reconfiguring it; the next song must still be heard.
        val effects = listOf(SignalProbe("in"), fr.spotifeur.playback.dsp.EqProcessor(), fr.spotifeur.playback.dsp.SpatialProcessor(),
            LoudnessProcessor(), fr.spotifeur.playback.dsp.EnergyMeter(), SignalProbe())
        val inner = RecordingSink()
        val sink = FloatSink(inner, effects) { false }
        sink.configure(AudioSink.AudioSinkConfig.Builder(pcm(C.ENCODING_PCM_16BIT, 48000, 2)).build())
        var at = 0L
        repeat(10) { assertTrue(sink.handleBuffer(sine16(2400, it * 2400), at, 1)); at += 50_000 }
        assertTrue("before the flush: ${rmsDbOfFloats(inner.bytes.toByteArray(), 2400)}", rmsDbOfFloats(inner.bytes.toByteArray(), 2400) > -20)
        sink.flush(); inner.bytes.reset()
        repeat(10) { assertTrue(sink.handleBuffer(sine16(2400, it * 2400), it * 50_000L, 1)) }
        assertTrue("after the flush: ${rmsDbOfFloats(inner.bytes.toByteArray(), 2400)}", rmsDbOfFloats(inner.bytes.toByteArray(), 2400) > -20)
    }

    @Test
    fun gaplessSongChangeKeepsPlaying() {
        // The next song in the same format reconfigures the sink: the old chain's held frames come out, then the new
        // song plays. This once looped forever on the limiter's end-of-stream tail (a stalled player, silence).
        val effects = listOf(fr.spotifeur.playback.dsp.EqProcessor(), LoudnessProcessor(), fr.spotifeur.playback.dsp.EnergyMeter())
        val inner = RecordingSink()
        val sink = FloatSink(inner, effects) { false }
        val config = AudioSink.AudioSinkConfig.Builder(pcm(C.ENCODING_PCM_16BIT, 48000, 2)).build()
        sink.configure(config)
        repeat(10) { assertTrue(sink.handleBuffer(sine16(2400, it * 2400), it * 50_000L, 1)) }
        sink.configure(config)
        repeat(10) { assertTrue(sink.handleBuffer(sine16(2400, (10 + it) * 2400), (10 + it) * 50_000L, 1)) }
        val latency = fr.spotifeur.playback.dsp.TruePeakLimiter(48000, 2).latencyFrames
        assertEquals("both songs, nothing lost or added", 20 * 2400 * 8, inner.bytes.size())
        assertTrue("the second song is heard", rmsDbOfFloats(inner.bytes.toByteArray().copyOfRange(10 * 2400 * 8, 20 * 2400 * 8), latency) > -20)
    }

    @Test
    fun passthroughStaysUntouched() {
        val inner = RecordingSink()
        val sink = FloatSink(inner, emptyList()) { false }
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setSampleRate(48000).setChannelCount(6).build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(ac3).build())
        assertEquals(MimeTypes.AUDIO_AC3, inner.format!!.sampleMimeType)
        val data = ByteArray(1536) { it.toByte() }
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(data), 0, 1))
        assertArrayEquals(data, inner.bytes.toByteArray())
    }
}
