package fr.spotifeur.playback

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.cancel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import fr.spotifeur.BuildConfig
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import fr.spotifeur.MainActivity
import fr.spotifeur.data.preferences.SettingsStore
import fr.spotifeur.data.SpotifyHlsDataSource

@UnstableApi
class SpotifeurPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var source: SpotifyHlsDataSource.Factory? = null
    private var nativeAudio: NativeAudioProvider? = null
    private var behavior: PlaybackBehavior? = null
    private var preloader: QueuePreloader? = null
    private var recovery: PlaybackRecovery? = null
    /** Each player levels its own track: during a crossfade both sides keep their own gain. */
    private val loudness = HashMap<ExoPlayer, LoudnessProcessor>()
    /** Each player's tone/headphone EQ and dimensional effect, ahead of its loudness stage. */
    private val sound = HashMap<ExoPlayer, Pair<fr.spotifeur.playback.dsp.EqProcessor, fr.spotifeur.playback.dsp.SpatialProcessor>>()
    private var pendingSound: Pair<fr.spotifeur.playback.dsp.EqProcessor, fr.spotifeur.playback.dsp.SpatialProcessor>? = null
    private val meters = HashMap<ExoPlayer, fr.spotifeur.playback.dsp.EnergyMeter>()
    private var pendingMeter: fr.spotifeur.playback.dsp.EnergyMeter? = null
    private var crossfade: Crossfade? = null
    private var active: ExoPlayer? = null
    private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private var describedForLoudness: String? = null
    private val effectsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.SupervisorJob())
    override fun onCreate() {
        super.onCreate()
        val audio = if (BuildConfig.NATIVE_RUNTIME) NativeAudioProvider(this).also { nativeAudio = it; currentNativeAudio = it } else null
        val http = SpotifyHlsDataSource.Factory(this, nativeAudio = audio != null).also { source = it }
        // Betamax's audio provider (h9v.b in the supplied APK) explicitly selects L3.
        // Configure the platform CDM before any session is opened; it retains all keys.
        val drm = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                FrameworkMediaDrm.newInstance(uuid).apply { setPropertyString("securityLevel", "L3") }
            }
            .setMultiSession(true).setPlayClearSamplesWithoutKeys(true)
            .build(HttpMediaDrmCallback("https://spclient.wg.spotify.com/widevine-license/v1/audio/license", false, http))
        fun renderers(onLoudness: (LoudnessProcessor) -> Unit) = object : DefaultRenderersFactory(this) {
            override fun getCodecAdapterFactory(): androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory {
                val factory = super.getCodecAdapterFactory()
                if (audio != null) return audio.codecFactory
                if (!BuildConfig.DEBUG) return factory
                return androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory { configuration ->
                    val codec = factory.createAdapter(configuration)
                    var count = 0
                    object : androidx.media3.exoplayer.mediacodec.MediaCodecAdapter by codec {
                        override fun queueSecureInputBuffer(index: Int, offset: Int, info: androidx.media3.decoder.CryptoInfo, timeUs: Long, flags: Int) {
                            if (++count % 200 == 1) android.util.Log.i("SpotifeurAudio", "secure mode=${info.mode} ivSize=${info.iv?.size} ivPresent=${info.iv?.any { it.toInt() != 0 }} sub=${info.numSubSamples} clear=${info.numBytesOfClearData?.sum()} encrypted=${info.numBytesOfEncryptedData?.sum()} blocks=${info.encryptedBlocks}/${info.clearBlocks} crypto=${configuration.crypto != null}")
                            codec.queueSecureInputBuffer(index, offset, info, timeUs, flags)
                        }
                    }
                }
            }
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                // EQ, then the dimensional effect, then loudness with its limiter last, so nothing clips; the debug
                // probe measures what is heard.
                val eq = fr.spotifeur.playback.dsp.EqProcessor()
                val spatial = fr.spotifeur.playback.dsp.SpatialProcessor()
                pendingSound = eq to spatial
                val processor = LoudnessProcessor().also(onLoudness)
                val meter = fr.spotifeur.playback.dsp.EnergyMeter().also { pendingMeter = it }
                val effects = arrayOf<androidx.media3.common.audio.AudioProcessor>(eq, spatial, processor, meter)
                val chain = if (BuildConfig.BUILD_TYPE == "debug") effects + SignalProbe() else effects
                return DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessors(chain).build()
            }
        }
        // Playlists have no length and stay out of both caches: the data source keeps those itself.
        val cacheFlags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS
        val recentAudio = CacheDataSource.Factory().setCache(AudioCache.cache(this)).setCacheKeyFactory(AudioCache.keys)
            .setUpstreamDataSourceFactory(http).setFlags(cacheFlags)
        // YouTube Music requests have no known length: they use their own chain, keyed by track.
        val youtube = youTubeCacheChain(this)
        // Both players share one audio session, so the system equalizer covers the two sides of a crossfade.
        val sessionId = androidx.media3.common.util.Util.generateAudioSessionIdV21(this)
        fun buildPlayer(): ExoPlayer {
        var processor: LoudnessProcessor? = null
        val built = ExoPlayer.Builder(this, renderers { processor = it })
            .setMediaSourceFactory(SpotifeurMediaSourceFactory(HlsMediaSource.Factory(
                CacheDataSource.Factory().setCache(DownloadCenter.cache(this)).setCacheKeyFactory(AudioCache.keys)
                    .setUpstreamDataSourceFactory(recentAudio)
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(cacheFlags)
            ).setLoadErrorHandlingPolicy(ResilientLoadPolicy()).apply {
                if (audio != null) setExtractorFactory(NativeHlsExtractor(audio))
                else { setDrmSessionManagerProvider { drm }; if (BuildConfig.DEBUG) setExtractorFactory(ExtractorProbe()) }
            }, ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this)),
                ProgressiveMediaSource.Factory(youtube.kept).setLoadErrorHandlingPolicy(ResilientLoadPolicy())))
            .setAudioAttributes(attributes, false)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        built.setAudioSessionId(sessionId)
        built.preloadConfiguration = ExoPlayer.PreloadConfiguration(NEXT_TRACK_PRELOAD_US)
        built.addAnalyticsListener(PlaybackDiagnostics)
        loudness[built] = checkNotNull(processor) { "The audio sink is built with the player." }
        sound[built] = checkNotNull(pendingSound)
        meters[built] = checkNotNull(pendingMeter)
        return built
        }
        val player = buildPlayer()
        val standby = buildPlayer()
        EqualizerRuntime.start(this, effectsScope)
        EqualizerRuntime.setAudioSessionId(sessionId)
        val helpers = object {
            fun attach(target: ExoPlayer) {
                active = target
                renderingPlayer = target
                target.setAudioAttributes(attributes, true)
                target.addListener(playerListener)
                preloader = QueuePreloader(this@SpotifeurPlaybackService, target, http, recentAudio, effectsScope, audio, youtube.recent).also { it.start() }
                recovery = PlaybackRecovery(this@SpotifeurPlaybackService, target, effectsScope, audio).also { it.start() }
                behavior = PlaybackBehavior(this@SpotifeurPlaybackService, target).also { it.start() }
                updateNormalization(target)
            }
            fun detach(target: ExoPlayer) {
                target.removeListener(playerListener)
                // The outgoing side of a crossfade fades out by volume, never by losing audio focus.
                target.setAudioAttributes(attributes, false)
                behavior?.close(); behavior = null
                preloader?.close(); preloader = null
                recovery?.close(); recovery = null
            }
        }
        helpers.attach(player)
        crossfade = Crossfade(effectsScope, player, standby,
            fadeMs = { SettingsStore.get(this).settings.value.crossfadeSeconds * 1000L },
            automix = { SettingsStore.get(this).settings.value.automix },
            outroOf = { p -> meters[p]?.outro == true },
            // Two encrypted engine decoders at once are not proven yet: engine tracks change gaplessly.
            canOverlap = { outgoing, incoming -> outgoing.localConfiguration?.uri?.scheme != "spotify" && incoming.localConfiguration?.uri?.scheme != "spotify" },
            onHandoff = { outgoing, incoming ->
                helpers.detach(outgoing)
                helpers.attach(incoming)
                mediaSession?.player = incoming
            }).also { it.start() }
        effectsScope.launch {
            SettingsStore.get(this@SpotifeurPlaybackService).settings.map { it.normalizationLevel }.distinctUntilChanged()
                .collect { active?.let(::updateNormalization) }
        }
        // Sound settings reach both players at once, so a crossfade never blends two different tones.
        effectsScope.launch {
            SettingsStore.get(this@SpotifeurPlaybackService).settings
                .map { listOf(it.toneBass, it.toneTreble, it.toneDynamic, it.headphoneEq, it.spatialAudio) }.distinctUntilChanged()
                .collect { applySound() }
        }
        val audioManager = getSystemService(android.media.AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) = refreshHeadphones()
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) = refreshHeadphones()
        }, android.os.Handler(mainLooper))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mediaSession = MediaSession.Builder(this, player).setSessionActivity(intent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (controller.packageName == packageName || controller.isTrusted) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.reject()
            }).build()
    }
    private fun refreshHeadphones() {
        effectsScope.launch { fr.spotifeur.playback.dsp.SoundRuntime.refreshHeadphones(this@SpotifeurPlaybackService); applySound() }
    }

    private fun applySound() {
        val settings = SettingsStore.get(this).settings.value
        val curve = fr.spotifeur.playback.dsp.SoundRuntime.curve(settings)
        val amount = fr.spotifeur.playback.dsp.SoundRuntime.spatialAmount(settings)
        sound.values.forEach { (eq, spatial) -> eq.curve = curve; spatial.amount = amount }
    }

    private val playerListener = object : androidx.media3.common.Player.Listener {
        // The first track's loudness is only known once its manifest is read, hence READY too.
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { active?.let(::updateNormalization); announce() }
        override fun onPlaybackStateChanged(playbackState: Int) { active?.let(::updateNormalization) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { announce() }
    }

    /** Friends' "Listening now", when the user shares it (see Social.nowPlaying). */
    private fun announce() {
        val player = active ?: return
        val item = player.currentMediaItem
        val meta = item?.mediaMetadata
        fr.spotifeur.social.Social.nowPlaying(item?.mediaId, meta?.title?.toString().orEmpty(), meta?.artist?.toString().orEmpty(),
            meta?.artworkUri?.toString(), player.isPlaying)
    }

    private fun updateNormalization(player: ExoPlayer) {
        val processor = loudness[player] ?: return
        val level = SettingsStore.get(this).settings.value.normalizationLevel
        val item = player.currentMediaItem
        val gain = if (item == null) null else TrackLoudness.gainDb(this, item.mediaId, level)
        // A Spotify track whose loudness is not known yet: record it once in the index, then look again.
        if (gain == null && item != null && level != TrackLoudness.OFF && item.mediaId.startsWith("spotify:track:") && describedForLoudness != item.mediaId) {
            describedForLoudness = item.mediaId
            effectsScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.spotifeur.data.AudioIndex.get(this@SpotifeurPlaybackService).describe(musicOf(item), played = false) }
                if (player.currentMediaItem?.mediaId == item.mediaId) updateNormalization(player)
            }
        }
        val linear = if (gain == null) 1f else Math.pow(10.0, gain / 20.0).toFloat()
        if (linear != processor.gain) android.util.Log.i("SpotifeurAudio", "loudness level=$level gain=${gain?.let { "%.1f".format(it) }}dB")
        processor.gain = linear
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onTaskRemoved(rootIntent: Intent?) {
        val keepPlaying = SettingsStore.get(this).settings.value.keepPlayingInBackground
        if (!keepPlaying) mediaSession?.player?.pause()
        if (!keepPlaying || mediaSession?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        behavior?.close(); behavior = null
        preloader?.close(); preloader = null
        recovery?.close(); recovery = null
        crossfade?.close(); crossfade = null
        effectsScope.cancel()
        EqualizerRuntime.setAudioSessionId(0)
        nativeAudio?.cancelPending()
        mediaSession?.release(); mediaSession = null
        loudness.keys.forEach { it.release() }; loudness.clear(); active = null; renderingPlayer = null
        nativeAudio?.close(); nativeAudio = null
        source?.close(); source = null
        super.onDestroy()
    }

    internal class YouTubeChain(val recent: CacheDataSource.Factory, val kept: CacheDataSource.Factory)

    internal companion object {
        /** Downloads first, then the recent cache, then the network. Nothing is written to downloads while listening. */
        fun youTubeCacheChain(context: android.content.Context): YouTubeChain {
            val recent = CacheDataSource.Factory().setCache(AudioCache.cache(context)).setCacheKeyFactory(AudioCache.keys)
                .setUpstreamDataSourceFactory(YouTubeDataSource.Factory(context)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val kept = CacheDataSource.Factory().setCache(DownloadCenter.cache(context)).setCacheKeyFactory(AudioCache.keys)
                .setUpstreamDataSourceFactory(recent).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            return YouTubeChain(recent, kept)
        }
        private const val NEXT_TRACK_PRELOAD_US = 30_000_000L
        /** Instrumented tests only. */
        @Volatile internal var currentNativeAudio: NativeAudioProvider? = null
    }

}

/**
 * The player that is rendering now, for Listen together: its position comes from the audio output itself, where the
 * media controller only extrapolates between session updates (it was seen jumping by up to 200 ms). Main thread only.
 */
@Volatile var renderingPlayer: ExoPlayer? = null
