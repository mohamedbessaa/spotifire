package fr.spotifeur.spotifire.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.AudioQuality
import fr.spotifeur.data.Music
import fr.spotifeur.data.model.toSong
import fr.spotifeur.data.source
import fr.spotifeur.playback.CollectionProgress
import fr.spotifeur.playback.DownloadCenter
import fr.spotifeur.playback.downloadProgressText
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.Pill
import fr.spotifeur.spotifire.components.TrackRow
import fr.spotifeur.spotifire.components.coverShape
import fr.spotifeur.spotifire.components.isLikedSongs
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle

private enum class DownloadsTab(val label: String) { Collections("Albums & playlists"), Songs("Songs") }

/**
 * Everything kept on the phone: what is downloading (with pause, resume and cancel), what is
 * ready offline, storage used and the download quality. It reads Spotifeur's DownloadCenter
 * directly, so what is shown here is exactly what playback can read without network.
 */
@Composable
fun SpotifireDownloadsScreen(model: SpotifeurModel, navController: NavHostController, bottomPadding: Int) {
    val ui = model.ui
    val context = LocalContext.current
    val collections by ui.downloadCollections.collectAsStateWithLifecycle()
    val tracks by ui.downloads.collectAsStateWithLifecycle()
    val preparing by ui.downloadPreparations.collectAsStateWithLifecycle()
    val stored by ui.downloadsStoredBytes.collectAsStateWithLifecycle()
    val paused by DownloadCenter.userPaused.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    val settings by ui.settings.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(DownloadsTab.Collections) }
    var confirmClear by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Music?>(null) }
    var removingTrack by remember { mutableStateOf<Music?>(null) }
    var options by remember { mutableStateOf<fr.spotifeur.data.model.Song?>(null) }

    val active = collections.values.filter { !it.isComplete }
    val done = collections.values.filter { it.isComplete }
    val songs = remember(tracks) { tracks.values.filter { it.isComplete }.map { it.track } }
    val songItems = remember(songs, saved) { songs.map { it.toSong(it.id in saved) } }
    val free = remember(stored) { ui.freeStorageBytes() }
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)

    SongOptionsHost(ui, navController, options, songItems, "Downloads") { options = null }
    if (confirmClear) ConfirmRemove("Remove all downloads?", "Everything kept for offline listening is deleted from this phone. Your playlists and likes stay.") {
        confirmClear = false; if (it) ui.removeAllDownloads()
    }
    removing?.let { item ->
        ConfirmRemove("Remove download?", "\"${item.title}\" will no longer be available offline.") { ok ->
            removing = null; if (ok) ui.removeDownloads(item)
        }
    }
    removingTrack?.let { track ->
        ConfirmRemove("Remove this song?", "\"${track.title}\" will no longer be available offline, even inside the album or playlist it came from.") { ok ->
            removingTrack = null; if (ok) DownloadCenter.removeTrack(context, track.id)
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(SpotifyColors.Background).statusBarsPadding(), contentPadding = PaddingValues(bottom = bottomPadding.dp)) {
        item {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text("Downloads", Modifier.weight(1f), color = Color.White, fontSize = 22.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
                if (songs.isNotEmpty()) IconButton(onClick = { ui.playSongsShuffled(songItems, "Downloads") }) {
                    Icon(Icons.Outlined.Shuffle, "Shuffle downloads", tint = Color.White)
                }
            }
        }
        // Storage, quality and offline mode
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Elevated).padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(size(stored), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("  used · ${size(free)} free", color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text("${songs.size} songs available offline", color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                val total = (stored + free).coerceAtLeast(1)
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(SpotifyColors.Highlight)) {
                    Box(Modifier.fillMaxWidth((stored.toFloat() / total).coerceIn(0.01f, 1f)).fillMaxHeight().background(SpotifyColors.Green))
                }
                Text("Download quality", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val current = AudioQuality.of(settings.downloadQuality).takeIf { it != AudioQuality.AUTO } ?: AudioQuality.VERY_HIGH
                    Pill("Normal", current == AudioQuality.NORMAL) { ui.updateSettings { it.copy(downloadQuality = AudioQuality.NORMAL.key) } }
                    Pill("Very high", current == AudioQuality.VERY_HIGH) { ui.updateSettings { it.copy(downloadQuality = AudioQuality.VERY_HIGH.key) } }
                }
                Text("Very high keeps the best copy of each song, up to lossless. Normal keeps 128 kbit/s where the source allows. It applies to new downloads.",
                    color = SpotifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Offline mode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Only play what is downloaded or cached. No network is used.", color = SpotifyColors.TextMuted, fontSize = 12.sp)
                    }
                    Switch(settings.offlineMode, { enabled -> ui.updateSettings { it.copy(offlineMode = enabled) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = SpotifyColors.Green, checkedThumbColor = Color.White))
                }
            }
        }
        if (!online) item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, tint = SpotifyColors.TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (settings.offlineMode) "Offline mode is on. Downloads resume when you turn it off."
                    else "You're offline. Downloads resume when the connection is back.", color = SpotifyColors.TextSecondary, fontSize = 13.sp)
            }
        }

        // In progress
        if (active.isNotEmpty() || preparing.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading", Modifier.weight(1f), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Pill(if (paused) "Resume all" else "Pause all", selected = paused) { DownloadCenter.setPaused(context, !paused) }
                }
            }
            items(preparing.filter { id -> collections[id] == null }.toList(), key = { "prep-$it" }) { id ->
                val item = ui.entity(id)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(item?.imageUrl, Modifier.size(52.dp), coverShape(item?.kind ?: fr.spotifeur.data.MusicKind.Playlist), liked = item?.isLikedSongs() == true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item?.title ?: "Preparing…", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Getting the track list…", color = SpotifyColors.TextSecondary, fontSize = 12.sp)
                    }
                    item?.let { IconButton(onClick = { ui.removeDownloads(it) }) { Icon(Icons.Filled.Close, "Cancel", tint = SpotifyColors.TextSecondary) } }
                }
            }
            items(active, key = { "a-" + it.collection.item.id }) { progress ->
                ActiveDownloadRow(progress, paused || !online, onOpen = { navController.openMusic(ui, progress.collection.item) },
                    onCancel = { ui.removeDownloads(progress.collection.item) })
            }
        }

        // Ready offline
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadsTab.entries.forEach { option -> Pill(option.label, tab == option) { tab = option } }
            }
        }
        if (done.isEmpty() && songs.isEmpty() && active.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.DownloadForOffline, null, tint = SpotifyColors.TextMuted, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Nothing downloaded yet", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Tap the download arrow on an album or playlist, or long-press a song and choose Download.", color = SpotifyColors.TextSecondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        when (tab) {
            DownloadsTab.Collections -> items(done, key = { "d-" + it.collection.item.id }) { progress ->
                val item = progress.collection.item
                Row(Modifier.fillMaxWidth().quietClick { navController.openMusic(ui, item) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Cover(item.imageUrl, Modifier.size(56.dp), coverShape(item.kind), liked = item.isLikedSongs())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DownloadForOffline, null, tint = SpotifyColors.Green, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${progress.total} songs", color = SpotifyColors.TextSecondary, fontSize = 13.sp)
                        }
                    }
                    IconButton(onClick = { removing = item }) { Icon(Icons.Filled.Delete, "Remove download", tint = SpotifyColors.TextSecondary) }
                }
            }
            DownloadsTab.Songs -> items(songs, key = { "s-" + it.id }) { track ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TrackRow(track.title, track.subtitle, track.imageUrl, track.source, playing = stable.currentSong?.id == track.id,
                            downloaded = true, liked = track.id in saved,
                            onClick = { ui.playSongs(songItems, songItems[songs.indexOf(track)], "Downloads") },
                            onMore = { options = track.toSong(track.id in saved) })
                    }
                    IconButton(onClick = { removingTrack = track }) { Icon(Icons.Filled.Delete, "Remove", tint = SpotifyColors.TextSecondary) }
                }
            }
        }
        if (done.isNotEmpty() || songs.isNotEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Remove all downloads", color = SpotifyColors.Error, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.quietClick { confirmClear = true })
            }
        }
    }
}

@Composable
private fun ActiveDownloadRow(progress: CollectionProgress, paused: Boolean, onOpen: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val item = progress.collection.item
    Row(Modifier.fillMaxWidth().quietClick(onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(item.imageUrl, Modifier.size(52.dp), coverShape(item.kind), liked = item.isLikedSongs())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                progress.failed -> "Some songs failed. Tap the download button again to retry."
                paused -> "Paused · ${progress.completed} of ${progress.total} songs"
                else -> downloadProgressText(context, progress.percent, progress.completed, progress.total, progress.remainingSeconds)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress.failed) { Icon(Icons.Filled.ErrorOutline, null, tint = SpotifyColors.Error, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                else if (paused) { Icon(Icons.Filled.Pause, null, tint = SpotifyColors.TextSecondary, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                else { Icon(Icons.Filled.PlayArrow, null, tint = SpotifyColors.Green, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                Text(status, color = if (progress.failed) SpotifyColors.Error else SpotifyColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
            }
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(SpotifyColors.Highlight)) {
                Box(Modifier.fillMaxWidth((progress.percent / 100f).coerceIn(0f, 1f)).fillMaxHeight()
                    .background(if (paused) SpotifyColors.TextMuted else SpotifyColors.Green))
            }
        }
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel download", tint = SpotifyColors.TextSecondary) }
    }
}

@Composable
private fun ConfirmRemove(title: String, body: String, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) }, containerColor = SpotifyColors.Elevated,
        title = { Text(title, color = Color.White) }, text = { Text(body, color = SpotifyColors.TextSecondary) },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text("Remove", color = SpotifyColors.Error) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Cancel", color = Color.White) } },
    )
}
