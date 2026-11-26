package fr.spotifeur.playback.dsp

import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * A processor that holds frames back (a limiter's lookahead, a resampler's kernel) and lets them out at the end of a
 * stream. Two Media3 habits make that easy to get wrong: its pipeline repeats queueEndOfStream on every pass, and the
 * base class reuses one output buffer, which a consumer may still be reading when the end is signalled. So the tail is
 * written once per stream, at the next request for output, when the previous output is known to be consumed.
 */
@UnstableApi
abstract class TailingAudioProcessor : BaseAudioProcessor() {
    private var tailDue = false
    private var tailed = false

    /** Writes the held frames with replaceOutputBuffer. Called at most once per stream. */
    protected abstract fun writeTail()

    /** What [onFlush] would do; the tail bookkeeping is reset first. */
    protected open fun onFlushed() {}

    final override fun onQueueEndOfStream() {
        if (tailed) return
        tailed = true
        tailDue = true
    }

    override fun getOutput(): ByteBuffer {
        val out = super.getOutput()
        if (out.hasRemaining() || !tailDue) return out
        tailDue = false
        writeTail()
        return super.getOutput()
    }

    override fun isEnded(): Boolean = super.isEnded() && !tailDue

    final override fun onFlush() { tailDue = false; tailed = false; onFlushed() }
}
