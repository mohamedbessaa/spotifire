package fr.spotifeur.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.sources.CopyResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * A 6 s crossfade between two Spotify tracks read from their copies: both players play at once during the blend, the
 * session moves to the incoming one, decoded PCM never stops, and the outgoing player ends stopped and empty.
 * Silent on the device (a last processor mutes what the probe has measured). Run with `-e crossfadeAudio true`.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CrossfadeSignalTest {
    private class Mute : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
            if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET
        override fun queueInput(inputBuffer: ByteBuffer) {
            val size = inputBuffer.remaining()
            val out = replaceOutputBuffer(size)
            repeat(size) { out.put(0) }
            inputBuffer.position(inputBuffer.limit())
            out.flip()
        }
    }

    @Test fun crossfadeOverlapsHandsOffAndKeepsSignal(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("crossfadeAudio") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        CopyResolver.init(app)
        val first = Music("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", "Blinding Lights", "The Weeknd", MusicKind.Track,
            durationMs = 200_040, album = "After Hours", isrc = "USUG11904206")
        val second = Music("spotify:track:69kOkLUCkxIZYexIgSG8rq", "Get Lucky (feat. Pharrell Williams & Nile Rodgers)",
            "Daft Punk, Pharrell Williams, Nile Rodgers", MusicKind.Track, durationMs = 369_626, album = "Random Access Memories",
            isrc = "USQX91300108")
        val tracks = listOf(first, second)
        val chain = SpotifeurPlaybackService.youTubeCacheChain(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val handoffs = mutableListOf<Pair<ExoPlayer, ExoPlayer>>()
        var crossfade: Crossfade? = null
        val players = mutableListOf<ExoPlayer>()
        try {
            withContext(Dispatchers.Main) {
                fun build() = ExoPlayer.Builder(app, object : DefaultRenderersFactory(app) {
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe(), Mute())).build()
                }).setMediaSourceFactory(ProgressiveMediaSource.Factory(chain.kept)).build().also { players += it }
                val a = build(); val b = build()
                a.setMediaItems(tracks.map { track ->
                    MediaItem.Builder().setMediaId(track.id).setUri(checkNotNull(YouTubeUris.of(track, spotifySignedIn = false)))
                        .setCustomCacheKey(YouTubeUris.cacheKey(track.id)).build()
                })
                a.prepare(); a.play()
                crossfade = Crossfade(scope, a, b, fadeMs = { 6_000L }, canOverlap = { _, _ -> true },
                    onHandoff = { outgoing, incoming -> handoffs += outgoing to incoming }).also { it.start() }
            }
            val outgoing = players[0]; val incoming = players[1]
            withTimeout(40_000) { while (withContext(Dispatchers.Main) { outgoing.playbackState } != androidx.media3.common.Player.STATE_READY) delay(100) }
            // Jump close to the end of the first track: the fade arms 5 s before it starts.
            withContext(Dispatchers.Main) { outgoing.seekTo(first.durationMs - 16_000L) }
            withTimeout(30_000) { while (handoffs.isEmpty()) delay(50) }
            Log.i(TAG, "handoff at ${withContext(Dispatchers.Main) { outgoing.currentPosition }} ms of the first track")
            var overlap = 0
            var lastSignalGap = 0L
            repeat(60) {
                val both = withContext(Dispatchers.Main) { outgoing.isPlaying && incoming.isPlaying }
                if (both) overlap++
                lastSignalGap = maxOf(lastSignalGap, android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get())
                delay(100)
            }
            Log.i(TAG, "both playing in $overlap of 60 samples over 6 s; longest gap without PCM ${lastSignalGap} ms")
            assertTrue("players overlap during the blend ($overlap)", overlap >= 30)
            assertTrue("PCM never stops (gap $lastSignalGap ms)", lastSignalGap < 1_000)
            delay(2_000)
            withContext(Dispatchers.Main) {
                assertEquals(1, handoffs.size)
                assertEquals(incoming, crossfade!!.active)
                assertEquals(second.id, incoming.currentMediaItem?.mediaId)
                assertTrue("incoming plays at full volume", incoming.isPlaying && incoming.volume == 1f)
                assertEquals("outgoing is emptied", 0, outgoing.mediaItemCount)
            }
            Log.i(TAG, "crossfade OK")
        } finally {
            withContext(Dispatchers.Main) { crossfade?.close(); players.forEach { it.release() } }
            scope.cancel()
            AudioCache.remove(app, tracks.map(Music::id))
        }
    }

    private companion object { const val TAG = "SpotifeurCrossfadeTest" }
}
