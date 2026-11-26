package fr.spotifeur.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.ToFloatPcmAudioProcessor
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The output stage: every PCM stream, whatever its depth (16-bit, 24-bit FLAC, float), becomes 32-bit float, goes
 * through [effects] (EQ, headphone correction, Dimensional, loudness and its true-peak limiter, the meter) and reaches
 * [inner] as float. Media3 1.11 skips custom processors when it writes float itself, so they run here, in front of it.
 *
 * Gapless trimming (encoder delay and padding) happens here first, because the limiter's lookahead would otherwise
 * shift the frames the inner sink trims; at a gapless song change only the trimming starts over and the effects run on. With "skip silence" on, the result is handed over as dithered 16-bit, the only
 * depth Media3's silence skipping reads. Anything that is not PCM (passthrough) goes straight to [inner].
 */
@UnstableApi
class FloatSink(inner: AudioSink, private val effects: List<AudioProcessor>, private val skipSilence: () -> Boolean) : ForwardingAudioSink(inner) {
    private val sink = inner
    /** Per-song gapless trimming, ahead of the chain so a song change resets it without touching the effects. */
    private val trimming = TrimmingAudioProcessor()
    private var pipeline: AudioProcessingPipeline? = null
    private var inputFormat: AudioProcessor.AudioFormat? = null
    private var sixteen = false
    /** Trimmed input the chain has not taken yet (the trimmer's output, or the caller's buffer itself). */
    private var trimmed: ByteBuffer = EMPTY
    /** Processed audio the inner sink has not taken yet, with the time of the input it came from. */
    private var pending: ByteBuffer = EMPTY
    private var pendingTimeUs = 0L
    private var draining = false
    private var store: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder())

    override fun getFormatSupport(format: Format): Int =
        if (isPcm(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else sink.getFormatSupport(format)

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        if (!isPcm(format)) { pipeline = null; sink.configure(audioSinkConfig); return }
        val incoming = AudioProcessor.AudioFormat(format)
        val wantSixteen = skipSilence()
        val running = pipeline
        // The next song in the same shape (a gapless change): the effects keep running, so the limiter carries the
        // end of one song straight into the next. Only the trimming starts over: the old song's padding is dropped.
        val keep = running != null && incoming == inputFormat && wantSixteen == sixteen && !draining
        if (keep) {
            if (trimming.isActive) { trimming.queueEndOfStream(); trimmed = trimming.output }
            while (trimmed.hasRemaining()) { running!!.queueInput(trimmed); take(running.output, pendingTimeUs) }
        } else {
            pending = EMPTY
            val built = ImmutableList.builder<AudioProcessor>().add(ToFloatPcmAudioProcessor()).addAll(effects)
            if (wantSixteen) built.add(Dither16Processor())
            val chain = AudioProcessingPipeline(built.build())
            try { chain.configure(incoming) } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                throw AudioSink.ConfigurationException(e, format)
            }
            chain.flush()
            pipeline = chain; inputFormat = incoming; sixteen = wantSixteen; draining = false
        }
        trimmed = EMPTY
        trimming.setTrimFrameCount(format.encoderDelay, format.encoderPadding)
        try { trimming.configure(incoming) } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            throw AudioSink.ConfigurationException(e, format)
        }
        trimming.flush()
        val out = pipeline!!.outputAudioFormat
        android.util.Log.i("SpotifeurSink", "configure in=${incoming.encoding}/${incoming.sampleRate}/${incoming.channelCount} " +
            "trim=${format.encoderDelay}+${format.encoderPadding} out=${out.encoding}/${out.sampleRate}/${out.channelCount} " +
            "kept=$keep sixteen=$wantSixteen")
        val outFormat = format.buildUpon().setPcmEncoding(out.encoding).setSampleRate(out.sampleRate).setChannelCount(out.channelCount)
            .setEncoderDelay(0).setEncoderPadding(0).build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(outFormat)
            .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
            .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
            .setTimeline(audioSinkConfig.timeline).setMediaPeriodId(audioSinkConfig.mediaPeriodId).build())
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val chain = pipeline ?: return sink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        while (true) {
            if (pending.hasRemaining()) {
                sink.handleBuffer(pending, pendingTimeUs, 1)
                if (pending.hasRemaining()) return false
            }
            if (!trimmed.hasRemaining()) {
                if (!buffer.hasRemaining()) return true
                trimmed = if (trimming.isActive) { trimming.queueInput(buffer); trimming.output } else buffer
                if (!trimmed.hasRemaining()) continue // all of it trimmed away (a song's priming)
            }
            val before = trimmed.remaining()
            chain.queueInput(trimmed)
            take(chain.output, presentationTimeUs)
            if (trimmed.remaining() == before && !pending.hasRemaining()) return false // the chain is full; try again later
        }
    }

    override fun playToEndOfStream() {
        val chain = pipeline ?: return sink.playToEndOfStream()
        if (!draining) {
            if (trimming.isActive) { trimming.queueEndOfStream(); trimmed = trimming.output }
            while (trimmed.hasRemaining()) { chain.queueInput(trimmed); take(chain.output, pendingTimeUs) }
            chain.queueEndOfStream(); draining = true
        }
        while (true) {
            if (pending.hasRemaining()) {
                sink.handleBuffer(pending, pendingTimeUs, 1)
                if (pending.hasRemaining()) return
            }
            val out = chain.output
            if (!out.hasRemaining()) break
            take(out, pendingTimeUs)
        }
        if (chain.isEnded) sink.playToEndOfStream()
    }

    override fun isEnded(): Boolean = sink.isEnded() && (pipeline == null || (!pending.hasRemaining() && pipeline!!.isEnded))

    override fun hasPendingData(): Boolean = pending.hasRemaining() || sink.hasPendingData()

    override fun flush() { pipeline?.flush(); trimming.flush(); trimmed = EMPTY; pending = EMPTY; draining = false; sink.flush() }

    override fun reset() {
        pipeline?.reset(); trimming.reset(); pipeline = null; inputFormat = null; trimmed = EMPTY; pending = EMPTY; draining = false
        sink.reset()
    }

    /** Copies the chain's output (its buffer is reused) into [pending], reusing one buffer while it is empty. */
    private fun take(out: ByteBuffer, timeUs: Long) {
        if (!out.hasRemaining()) return
        if (!pending.hasRemaining()) {
            pendingTimeUs = timeUs
            if (store.capacity() < out.remaining()) store = ByteBuffer.allocateDirect(out.remaining() * 2).order(ByteOrder.nativeOrder())
            store.clear(); store.put(out).flip(); pending = store
        } else {
            val merged = ByteBuffer.allocate(pending.remaining() + out.remaining()).order(ByteOrder.nativeOrder())
            merged.put(pending).put(out).flip(); pending = merged
        }
    }

    private fun isPcm(format: Format) = MimeTypes.AUDIO_RAW == format.sampleMimeType && Util.isEncodingLinearPcm(format.pcmEncoding)

    private companion object { val EMPTY: ByteBuffer = ByteBuffer.allocate(0) }
}

/** Float to 16-bit with TPDF dither: the last bit is noise instead of a stepped error the ear can follow. */
@UnstableApi
class Dither16Processor : BaseAudioProcessor() {
    private val random = java.util.Random(0x5EED)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
            AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT)
        else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val count = input.remaining() / 4
        if (count == 0) return
        val output = replaceOutputBuffer(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) {
            val dither = random.nextFloat() - random.nextFloat()
            output.putShort(Math.round(input.float * 32767f + dither).coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }
}
