package fr.spotifeur.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import fr.spotifeur.data.sources.Copy
import fr.spotifeur.data.sources.CopyResolver
import fr.spotifeur.data.youtube.MatchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decoded signal of Spotify tracks read from their Monochrome copy (same ISRC, AAC 320): past the lead-in, after
 * seeks and across the transition between two tracks. Silent on the device (volume 0: the probe reads the decoded
 * PCM before the volume is applied). Run with `-e copyAudio true`; network required.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CopyPlaybackSignalTest {
    @Test fun monochromeCopySignalAfterLeadSeeksAndTransition(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("copyAudio") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        CopyResolver.init(app)
        AudioRoute.init(app)
        val first = Music("spotify:track:69kOkLUCkxIZYexIgSG8rq", "Get Lucky (feat. Pharrell Williams & Nile Rodgers)",
            "Daft Punk, Pharrell Williams, Nile Rodgers", MusicKind.Track, durationMs = 369_626, album = "Random Access Memories",
            isrc = "USQX91300108")
        val second = Music("spotify:track:0VjIjW4GlUZAMYd2vXMi3b", "Blinding Lights", "The Weeknd", MusicKind.Track,
            durationMs = 200_040, album = "After Hours", isrc = "USUG11904206")
        val tracks = listOf(first, second)
        tracks.forEach { CopyResolver.forget(it.id) }
        // The copy chosen for each track is the same recording on Monochrome.
        tracks.forEach { track ->
            val copy = withContext(Dispatchers.IO) {
                CopyResolver.resolve(MatchQuery(track.id, track.title, track.subtitle, track.durationMs, track.album, track.isrc))
            }
            Log.i(TAG, "${track.id} copy=$copy")
            assertTrue("${track.id} reads Monochrome, got $copy", copy is Copy.Monochrome)
        }
        val chain = SpotifeurPlaybackService.youTubeCacheChain(app)
        var player: ExoPlayer? = null
        suspend fun signal(label: String) {
            val before = PlaybackDiagnostics.nonSilentBuffers.get()
            withTimeout(40_000) {
                while (PlaybackDiagnostics.nonSilentBuffers.get() < before + 20) {
                    val error = withContext(Dispatchers.Main) { player!!.playerError }
                    check(error == null) { "Playback error=${error?.errorCodeName} cause=${generateSequence<Throwable>(error) { it.cause }.joinToString(">") { it.toString() }}" }
                    delay(100)
                }
            }
            assertTrue("$label: fresh PCM signal", android.os.SystemClock.elapsedRealtime() - PlaybackDiagnostics.lastSignalAt.get() < 2000)
            val format = withContext(Dispatchers.Main) { player!!.audioFormat }
            Log.i(TAG, "$label PCM OK format=${format?.sampleMimeType} ${format?.sampleRate}Hz ${format?.bitrate}bps")
        }
        try {
            withContext(Dispatchers.Main) {
                val renderers = object : DefaultRenderersFactory(app) {
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                        DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(SignalProbe())).build()
                }
                player = ExoPlayer.Builder(app, renderers).build().apply {
                    addAnalyticsListener(PlaybackDiagnostics)
                    volume = 0f
                    val factory = ProgressiveMediaSource.Factory(chain.kept)
                    setMediaSources(tracks.map { track ->
                        val uri = checkNotNull(YouTubeUris.of(track, spotifySignedIn = false))
                        factory.createMediaSource(MediaItem.Builder().setMediaId(track.id).setUri(uri)
                            .setCustomCacheKey(YouTubeUris.cacheKey(track.id)).build())
                    })
                    prepare(); play()
                }
            }
            signal("start")
            delay(20_000)
            signal("continuous")
            for (fraction in listOf(0.2, 0.7, 0.5)) {
                withContext(Dispatchers.Main) { player!!.seekTo((first.durationMs * fraction).toLong()) }
                signal("seek/$fraction")
            }
            withContext(Dispatchers.Main) { player!!.seekTo(first.durationMs - 6_000L) }
            withTimeout(30_000) { while (withContext(Dispatchers.Main) { player!!.currentMediaItemIndex } != 1) delay(200) }
            signal("after transition")
            delay(15_000)
            signal("continuous second")
            withContext(Dispatchers.Main) { player!!.seekTo(1, (second.durationMs * 0.6).toLong()) }
            signal("seek second/0.6")
            assertEquals(Player.STATE_READY, withContext(Dispatchers.Main) { player!!.playbackState })
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            AudioCache.remove(app, tracks.map(Music::id))
            tracks.forEach { CopyResolver.forget(it.id) }
        }
    }

    private companion object { const val TAG = "SpotifeurCopyTest" }
}
