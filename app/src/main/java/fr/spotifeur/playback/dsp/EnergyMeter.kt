package fr.spotifeur.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens to what a player renders, without changing it: the level of the last 400 ms and a slow average of the
 * track. Automix reads them to find the outro, where a song winds down, and blend there.
 */
@UnstableApi
class EnergyMeter : BaseAudioProcessor() {
    /** dBFS of the last window. */
    @Volatile var shortDb: Float = -90f
        private set
    /** Running average of the track in dBFS (about 12 s), reset at each new stream. */
    @Volatile var longDb: Float = -90f
        private set
    private var squares = 0.0
    private var count = 0L
    private var windows = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun onFlush() { squares = 0.0; count = 0; windows = 0; shortDb = -90f; longDb = -90f }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val samples = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (samples.remaining() >= 2) { val v = samples.short.toDouble(); squares += v * v; count++ }
        val window = inputAudioFormat.sampleRate.toLong() * inputAudioFormat.channelCount * 2 / 5
        if (count >= window) {
            val db = (20 * log10((sqrt(squares / count) / 32768).coerceAtLeast(1e-5))).toFloat()
            shortDb = db
            lastRenderedDb = db
            windows++
            // Slow average; the first windows set it directly so an intro does not drag it down for long.
            longDb = if (windows <= 3) maxOf(longDb, db) else longDb + (db - longDb) * 0.033f
            squares = 0.0; count = 0
        }
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    companion object {
        /** The level of the latest window any player decoded: proof of sound for Listen together's sync log. */
        @Volatile var lastRenderedDb: Float = -90f
    }

    /** True once the song has clearly wound down: 9 dB under its own average, after it has been heard a while. */
    val outro: Boolean get() = windows > 25 && longDb > -50f && shortDb < longDb - 9f
}
