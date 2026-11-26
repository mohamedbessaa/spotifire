package fr.spotifeur.spotifire.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.MusicSource
import fr.spotifeur.data.model.Lyrics
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toMusic
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.SourceBadge
import fr.spotifeur.spotifire.components.TrackRow
import fr.spotifeur.spotifire.components.formatTime
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.components.rememberArtworkColor
import fr.spotifeur.spotifire.theme.SpotifyColors

private enum class PlayerPane { Player, Queue, Lyrics }

/** Spotify's now-playing screen. Swipe down or press back to close it. */
@Composable
fun SpotifireNowPlaying(model: SpotifeurModel, navController: NavHostController, onClose: () -> Unit) {
    val ui = model.ui
    val context = LocalContext.current
    val state by ui.stablePlayerState.collectAsStateWithLifecycle()
    val song = state.currentSong ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val position by ui.currentPlaybackPosition.collectAsStateWithLifecycle()
    // Lyrics follow the music frame by frame; the player only reports its position every 300 ms.
    val lyricsPosition = rememberSmoothPosition(position, state.isPlaying)
    val favorite by ui.isCurrentSongFavorite.collectAsStateWithLifecycle()
    val playerUi by ui.playerUiState.collectAsStateWithLifecycle()
    val queue by ui.queueFlow.collectAsStateWithLifecycle()
    val slice by ui.fullPlayerSlice.collectAsStateWithLifecycle()
    val timer by ui.activeTimerValueDisplay.collectAsStateWithLifecycle()
    var pane by remember { mutableStateOf(PlayerPane.Player) }
    var options by remember { mutableStateOf<Song?>(null) }
    var sleepMenu by remember { mutableStateOf(false) }
    val tint by animateColorAsState(rememberArtworkColor(song.albumArtUriString, Color(0xFF404040)), tween(700), label = "player")
    BackHandler { if (pane != PlayerPane.Player) pane = PlayerPane.Player else onClose() }
    LaunchedEffect(song.id) { ui.loadLyricsForCurrentSong() }

    SongOptionsHost(ui, navController, options, queue, playerUi.currentQueueSourceName) { options = null }

    // The glow stays still: the Canvas is what moves, and a looping zoom only cost battery.
    val zoom = 1.06f
    val scroll = rememberScrollState()
    var dragged by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { dragged = 0f },
                onVerticalDrag = { _, amount -> dragged += amount },
                onDragEnd = { if (dragged > 220f && scroll.value == 0) onClose() },
            )
        }
    ) {
        val screenHeight = maxHeight
        // Background: a glow made from the artwork. The cover's main color as a gradient, and on
        // Android 12+ the cover itself, blurred so much that only its colors remain, slowly drifting.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.85f), Color.Black))))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Cover(
                song.albumArtUriString,
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom * 1.4f; scaleY = zoom * 1.4f; alpha = 0.75f }.blur(90.dp),
                RectangleShape,
            )
        }
        // Canvas: the track's looping video, when it has one, over the glow and under the same shade.
        val canvasOn = ui.settings.collectAsStateWithLifecycle().value.canvas
        val canvasUrl by androidx.compose.runtime.produceState<String?>(null, song.id, canvasOn) {
            // The previous song's clip goes at once; the new one shows when it is known.
            value = null
            value = if (canvasOn) fr.spotifeur.data.SpotifyCanvas.videoFor(song) else null
        }
        val canvas = canvasUrl?.takeIf { pane == PlayerPane.Player }
        if (canvas != null) fr.spotifeur.spotifire.components.CanvasVideo(canvas, state.isPlaying, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.25f), 0.35f to Color.Transparent, 0.62f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.9f),
        )))
        if (pane != PlayerPane.Player) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, Color.Black))))

        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (pane != PlayerPane.Player) pane = PlayerPane.Player else onClose() }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when (pane) {
                        PlayerPane.Queue -> "Queue"; PlayerPane.Lyrics -> "Lyrics"
                        else -> if (playerUi.currentQueueSourceName.isNotBlank()) "Playing from Playlist" else "Playing from Album"
                    }, color = Color.White, fontSize = 13.sp)
                    Text(playerUi.currentQueueSourceName.ifBlank { song.album }, color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { options = song }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }

            when (pane) {
                PlayerPane.Queue -> Column(Modifier.weight(1f).padding(horizontal = 20.dp).navigationBarsPadding()) {
                    QueuePane(ui, queue, song, playerUi.currentQueueSourceName, Modifier.weight(1f)) { options = it }
                    SeekBar(position, state.totalDuration) { ui.seekTo(it) }
                    Controls(ui, state.isPlaying, state.isBuffering, state.isShuffleEnabled, state.repeatMode, state.isSmartShuffle)
                }
                PlayerPane.Lyrics -> Column(Modifier.weight(1f).padding(horizontal = 20.dp).navigationBarsPadding()) {
                    LyricsPane(state.lyrics, state.isLoadingLyrics, lyricsPosition, tint, Modifier.weight(1f)) { ui.seekTo(it) }
                    SeekBar(position, state.totalDuration) { ui.seekTo(it) }
                    Controls(ui, state.isPlaying, state.isBuffering, state.isShuffleEnabled, state.repeatMode, state.isSmartShuffle)
                }
                PlayerPane.Player -> Column(Modifier.weight(1f).verticalScroll(scroll)) {
                    // First "page": everything sits at the bottom, over the artwork.
                    Column(
                        Modifier.fillMaxWidth().height(screenHeight - 64.dp).padding(horizontal = 24.dp).navigationBarsPadding(),
                    ) {
                        Spacer(Modifier.weight(1f))
                        // The cover, medium size, in the middle. Swipe it to change track.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            // With a Canvas the video is the artwork: the cover leaves its place empty (still swipeable).
                            if (canvas == null) Cover(
                                song.albumArtUriString,
                                Modifier.fillMaxWidth(0.78f).widthIn(max = 340.dp).aspectRatio(1f)
                                    .shadow(32.dp, RoundedCornerShape(10.dp), ambientColor = tint, spotColor = Color.Black)
                                    .pointerInput(song.id) {
                                        var drag = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { drag = 0f },
                                            onHorizontalDrag = { _, amount -> drag += amount },
                                            onDragEnd = { if (drag < -140f) ui.nextSong() else if (drag > 140f) ui.previousSong() },
                                        )
                                    },
                                RoundedCornerShape(10.dp),
                            ) else Box(Modifier.fillMaxWidth(0.78f).widthIn(max = 340.dp).aspectRatio(1f).pointerInput(song.id) {
                                var drag = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { drag = 0f },
                                    onHorizontalDrag = { _, amount -> drag += amount },
                                    onDragEnd = { if (drag < -140f) ui.nextSong() else if (drag > 140f) ui.previousSong() },
                                )
                            })
                        }
                        Spacer(Modifier.weight(1f))
                        // The line being sung right now, as Spotify shows it over the Canvas.
                        val line = state.lyrics?.synced?.getOrNull(activeLine(state.lyrics, lyricsPosition))?.line?.takeIf(String::isNotBlank)
                        if (line != null) Text(line, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 16.dp).quietClick { pane = PlayerPane.Lyrics })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(song.title, color = Color.White, fontSize = 24.sp, fontFamily = fr.spotifeur.spotifire.theme.SpotifyMixTitle,
                                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.quietClick { onClose(); ui.openAlbumOf(song) })
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (song.source != MusicSource.Spotify) { SourceBadge(song.source, size = 16.dp); Spacer(Modifier.width(6.dp)) }
                                    Text(song.displayArtist, color = Color.White.copy(alpha = 0.75f), fontSize = 17.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.quietClick { onClose(); ui.openArtistOf(song) })
                                }
                            }
                            IconButton(onClick = { ui.toggleFavorite() }, modifier = Modifier.size(48.dp)) {
                                if (favorite) Icon(Icons.Filled.CheckCircle, "Saved", tint = SpotifyColors.Green, modifier = Modifier.size(34.dp))
                                else Icon(Icons.Outlined.AddCircleOutline, "Save", tint = Color.White, modifier = Modifier.size(34.dp))
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        SeekBar(position, state.totalDuration) { ui.seekTo(it) }
                        slice.audioMetaText?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp) }
                        Spacer(Modifier.height(8.dp))
                        Controls(ui, state.isPlaying, state.isBuffering, state.isShuffleEnabled, state.repeatMode, state.isSmartShuffle)
                        // Bottom row: output device, sleep timer, share, queue
                        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { openOutputSwitcher(context) }) {
                                Icon(Icons.Outlined.Speaker, "Devices", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            Box {
                                IconButton(onClick = { sleepMenu = true }) {
                                    Icon(Icons.Outlined.Timer, "Sleep timer", tint = if (timer != null) SpotifyColors.Green else Color.White, modifier = Modifier.size(26.dp))
                                }
                                SleepMenu(sleepMenu, timer != null, { sleepMenu = false }, { ui.setSleepTimer(it) }, { ui.setEndOfTrackTimer(true) }, { ui.cancelSleepTimer() })
                            }
                            timer?.let { Text(it, color = SpotifyColors.Green, fontSize = 12.sp) }
                            Spacer(Modifier.weight(1f))
                            if (!song.isLocal) IconButton(onClick = { context.let { ui.share(song.toMusic()) } }) {
                                Icon(Icons.Outlined.Share, "Share", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            IconButton(onClick = { pane = PlayerPane.Queue }) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    // Below the fold: the lyrics card, then some room.
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        LyricsPreview(state.lyrics, lyricsPosition, tint) { pane = PlayerPane.Lyrics }
                        Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

/** Android's own "play on" panel (phone speaker, Bluetooth, cast), or Bluetooth settings on older phones. */
private fun openOutputSwitcher(context: android.content.Context) {
    val panel = android.content.Intent("android.settings.panel.action.MEDIA_OUTPUT")
        .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(panel) }.onFailure {
        runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Spotify's thin seek bar: a 3 dp line with a small white dot, draggable and tappable. */
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth().height(24.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(duration) {
                detectTapGestures { offset -> onSeek(((offset.x / width).coerceIn(0f, 1f) * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = (offset.x / width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let { onSeek((it * duration).toLong()) }; dragging = null },
                    onDragCancel = { dragging = null },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        val dot = if (dragging != null) 16.dp else 12.dp
        Box(Modifier.fillMaxWidth(fraction).height(dot), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(dot).graphicsLayer { translationX = size.width / 2 }.clip(CircleShape).background(Color.White))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(formatTime(dragging?.let { (it * duration).toLong() } ?: position), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

/**
 * Shuffle, previous, the big white play button, next and repeat, in one row as in the Spotify app. Shuffle and repeat
 * turn green with a dot under them when on; the sleep timer sits in the row below.
 */
@Composable
private fun Controls(
    ui: fr.spotifeur.presentation.viewmodel.PlayerViewModel,
    playing: Boolean,
    buffering: Boolean,
    shuffle: Boolean,
    repeat: Int,
    smart: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { ui.toggleShuffle() }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Smart Shuffle: the shuffle glyph with a sparkle, as in Spotify.
                Box {
                    Icon(Icons.Filled.Shuffle, if (smart) "Smart Shuffle" else "Shuffle", tint = if (shuffle) SpotifyColors.Green else Color.White, modifier = Modifier.size(28.dp))
                    if (smart) Icon(Icons.Filled.AutoAwesome, null, tint = SpotifyColors.Green,
                        modifier = Modifier.size(12.dp).align(Alignment.TopEnd).offset(x = 5.dp, y = (-4).dp))
                }
                if (shuffle) Box(Modifier.size(4.dp).clip(CircleShape).background(SpotifyColors.Green))
            }
        }
        IconButton(onClick = { ui.previousSong() }, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(46.dp))
        }
        Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White).quietClick { ui.playPause() }, contentAlignment = Alignment.Center) {
            if (buffering && !playing) androidx.compose.material3.CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            else Icon(painterResource(if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com), if (playing) "Pause" else "Play",
                tint = Color.Black, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = { ui.nextSong() }, modifier = Modifier.size(60.dp)) {
            Icon(Icons.Filled.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(46.dp))
        }
        IconButton(onClick = { ui.cycleRepeatMode() }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat",
                    tint = if (repeat != Player.REPEAT_MODE_OFF) SpotifyColors.Green else Color.White, modifier = Modifier.size(28.dp))
                if (repeat != Player.REPEAT_MODE_OFF) Box(Modifier.size(4.dp).clip(CircleShape).background(SpotifyColors.Green))
            }
        }
    }
}

@Composable
private fun SleepMenu(open: Boolean, active: Boolean, onDismiss: () -> Unit, onMinutes: (Int) -> Unit, onEndOfTrack: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.material3.DropdownMenu(open, onDismiss, containerColor = SpotifyColors.Elevated) {
        listOf(5, 15, 30, 45, 60).forEach { minutes ->
            androidx.compose.material3.DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = { onDismiss(); onMinutes(minutes) })
        }
        androidx.compose.material3.DropdownMenuItem(text = { Text("End of track") }, onClick = { onDismiss(); onEndOfTrack() })
        if (active) androidx.compose.material3.DropdownMenuItem(text = { Text("Turn off timer", color = SpotifyColors.Error) }, onClick = { onDismiss(); onCancel() })
    }
}

@Composable
private fun QueuePane(
    ui: fr.spotifeur.presentation.viewmodel.PlayerViewModel,
    queue: List<Song>,
    current: Song,
    source: String,
    modifier: Modifier,
    onMore: (Song) -> Unit,
) {
    // Smart Shuffle's picks, as the player knows them.
    val playback by ui.model.player.state.collectAsStateWithLifecycle()
    val recommended = remember(playback.queue) { playback.queue.filter { it.note == fr.spotifeur.playback.SMART_NOTE }.map { it.id }.toSet() }
    val index = queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Text("Now playing", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            TrackRow(current.title, current.displayArtist, current.albumArtUriString, current.source, playing = true,
                onClick = {}, onMore = { onMore(current) }, modifier = Modifier.padding(end = 0.dp))
        }
        val upcoming = queue.drop(index + 1)
        if (upcoming.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (source.isNotBlank()) "Next from: $source" else "Next up", Modifier.weight(1f), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Clear", color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.quietClick { ui.clearQueueExceptCurrent() })
            }
        }
        itemsIndexed(upcoming, key = { i, song -> "$i-${song.id}" }) { i, song ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    val picked = song.id in recommended
                    TrackRow(song.title, if (picked) "Recommended · ${song.displayArtist}" else song.displayArtist, song.albumArtUriString, song.source, playing = false,
                        onClick = { ui.showAndPlaySong(song, queue, source, index + 1 + i) }, onMore = { onMore(song) })
                }
                IconButton(onClick = { ui.removeSongFromQueue(song.id) }) { Icon(Icons.Filled.Close, "Remove", tint = SpotifyColors.TextSecondary) }
            }
        }
    }
}

/**
 * The song position advanced every frame while playing, between the player's readings (every 300 ms). A reading close
 * to the running estimate keeps it, so the lyrics never step back; a seek or a pause takes the reading as it is.
 */
@Composable
private fun rememberSmoothPosition(reported: Long, playing: Boolean): Long {
    var smooth by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(reported) }
    androidx.compose.runtime.LaunchedEffect(reported, playing) {
        val base = if (playing && kotlin.math.abs(smooth - reported) < 150) smooth else reported
        smooth = base
        if (!playing) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) { val frame = androidx.compose.runtime.withFrameNanos { it }; smooth = base + (frame - start) / 1_000_000 }
    }
    return smooth
}

/** Index of the synced line being sung at [position]. */
private fun activeLine(lyrics: Lyrics?, position: Long): Int {
    val lines = lyrics?.synced ?: return -1
    return lines.indexOfLast { it.time <= position }
}

@Composable
private fun LyricsPreview(lyrics: Lyrics?, position: Long, tint: Color, onOpen: () -> Unit) {
    val synced = lyrics?.synced
    val plain = lyrics?.plain
    if (synced.isNullOrEmpty() && plain.isNullOrEmpty()) return
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 1f)).quietClick(onOpen).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lyrics, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Lyrics preview", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        val lines = synced?.map { it.line } ?: plain.orEmpty()
        val active = activeLine(lyrics, position).coerceAtLeast(0)
        lines.drop(active).take(4).forEachIndexed { i, line ->
            Text(line, color = if (i == 0 && synced != null) Color.White else Color.White.copy(alpha = 0.55f), fontSize = 20.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun LyricsPane(lyrics: Lyrics?, loading: Boolean, position: Long, tint: Color, modifier: Modifier, onSeek: (Long) -> Unit) {
    val synced = lyrics?.synced
    val active = activeLine(lyrics, position)
    val listState = rememberLazyListState()
    LaunchedEffect(active) { if (active > 2) listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tint)) {
        when {
            loading -> fr.spotifeur.spotifire.components.SpotifyLoader(Modifier.align(Alignment.Center))
            synced.isNullOrEmpty() && lyrics?.plain.isNullOrEmpty() ->
                Text("No lyrics for this song.", Modifier.align(Alignment.Center), color = Color.White, fontSize = 16.sp)
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp)) {
                if (synced != null) itemsIndexed(synced) { i, line ->
                    val upcoming = Color.Black.copy(alpha = 0.55f)
                    val words = line.words
                    // The line being sung fills word by word when the lyrics carry word timing.
                    val text = if (i == active && !words.isNullOrEmpty()) androidx.compose.ui.text.buildAnnotatedString {
                        words.forEachIndexed { index, word ->
                            // Words are stored trimmed; the space between two words comes back here.
                            if (index > 0 && word.startsNewWord) append(' ')
                            withStyle(androidx.compose.ui.text.SpanStyle(color = if (word.time <= position) Color.White else upcoming)) { append(word.word) }
                        }
                    } else androidx.compose.ui.text.AnnotatedString(line.line.ifBlank { "♪" })
                    Text(text, color = when {
                        i == active -> Color.White
                        i < active -> Color.White.copy(alpha = 0.75f)
                        else -> upcoming
                    }, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp,
                        modifier = Modifier.padding(vertical = 6.dp).quietClick { onSeek(line.time.toLong()) })
                } else itemsIndexed(lyrics?.plain.orEmpty()) { _, line ->
                    Text(line, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}
