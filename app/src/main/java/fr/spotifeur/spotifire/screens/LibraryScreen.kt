package fr.spotifeur.spotifire.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicSource
import fr.spotifeur.data.model.toSong
import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.spotifire.SpotifireRoutes
import fr.spotifeur.spotifire.components.CollectionRow
import fr.spotifeur.spotifire.components.Pill
import fr.spotifeur.spotifire.components.SpotifyLoader
import fr.spotifeur.spotifire.components.TrackRow
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle

/** Library filters. The first four are Spotify's own chips; the rest come from Spotifeur. */
enum class LibraryFilter(val label: String) {
    Playlists("Playlists"), Albums("Albums"), Artists("Artists"), Downloaded("Downloaded"),
    YouTube("YouTube Music"), Mixed("Mixed"), Local("On this phone"),
}

private enum class LibrarySort(val label: String) { Recents("Recents"), Alphabetical("Alphabetical"), Creator("Creator") }

@Composable
fun SpotifireLibraryScreen(model: SpotifeurModel, navController: NavHostController, bottomPadding: Int) {
    val ui = model.ui
    val library by model.library.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val youTube by model.youTubePlaylists.collectAsStateWithLifecycle()
    val youTubeLoading by model.youTubeLoading.collectAsStateWithLifecycle()
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val localTracks by model.localTracks.collectAsStateWithLifecycle()
    val downloads by ui.downloads.collectAsStateWithLifecycle()
    val downloadCollections by ui.downloadCollections.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Recents) }
    var sortMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf<Music?>(null) }
    var trackOptions by remember { mutableStateOf<Music?>(null) }
    val content = library.content

    MusicOptionsHost(ui, navController, options) { options = null }
    trackOptions?.let { item ->
        val song = item.toSong(item.id in saved)
        SongOptionsHost(ui, navController, song, listOf(song), "Your Library") { trackOptions = null }
    }
    if (creating) fr.spotifeur.presentation.components.SpotifyPlaylistEditor(model, onDismiss = { creating = false },
        onSaved = { created -> creating = false; created?.let { ui.register(it); navController.openMusic(ui, it) } })
    if (importing) fr.spotifeur.presentation.components.ImportPlaylistDialog(model, onDismiss = { importing = false }) { imported ->
        importing = false; navController.openMusic(ui, imported)
    }

    // Collections shown for the current chip.
    val multi = remember(localPlaylists) { localPlaylists.map { it.asMusic() } }
    val collections: List<Music> = remember(content, youTube, multi, filter, downloadCollections, pins, sort, text) {
        val spotifyPlaylists = content?.playlists.orEmpty()
        val all = when (filter) {
            null -> multi + youTube + spotifyPlaylists + content?.albums.orEmpty() + content?.artists.orEmpty()
            LibraryFilter.Playlists -> multi + youTube + spotifyPlaylists
            LibraryFilter.Albums -> content?.albums.orEmpty()
            LibraryFilter.Artists -> content?.artists.orEmpty()
            LibraryFilter.Downloaded -> downloadCollections.values.filter { it.isComplete }.map { it.collection.item }
            LibraryFilter.YouTube -> youTube
            LibraryFilter.Mixed -> multi
            LibraryFilter.Local -> emptyList()
        }.distinctBy(Music::id).filter { text.isBlank() || it.title.contains(text, true) || it.subtitle.contains(text, true) }
        val sorted = when (sort) {
            LibrarySort.Recents -> all
            LibrarySort.Alphabetical -> all.sortedBy { fr.spotifeur.presentation.viewmodel.sortableTitle(it.title).lowercase() }
            LibrarySort.Creator -> all.sortedBy { it.subtitle.lowercase() }
        }
        // Pinned items first, as in Spotify.
        val pinnedIds = pins.map(Music::id).toSet()
        sorted.sortedByDescending { it.id in pinnedIds }
    }
    val tracks: List<Music> = remember(filter, localTracks, text) {
        if (filter != LibraryFilter.Local) emptyList()
        else localTracks.filter { text.isBlank() || it.title.contains(text, true) || it.subtitle.contains(text, true) }
    }
    val showLiked = (filter == null || filter == LibraryFilter.Playlists) && (content?.savedTrackCount ?: 0) > 0 && text.isBlank()

    Column(Modifier.fillMaxSize().background(SpotifyColors.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            fr.spotifeur.spotifire.components.ProfileAvatar(model, 32.dp) { navController.navigateSafely(SpotifireRoutes.ACCOUNTS) }
            Spacer(Modifier.width(12.dp))
            if (searching) {
                Row(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(SpotifyColors.Highlight)
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = SpotifyColors.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Text("Search in Your Library", color = SpotifyColors.TextSecondary, fontSize = 14.sp)
                        BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            cursorBrush = SolidColor(SpotifyColors.Green), modifier = Modifier.fillMaxWidth())
                    }
                }
                IconButton(onClick = { searching = false; text = "" }) { Icon(Icons.Filled.Close, "Close search", tint = Color.White) }
            } else {
                Text("Your Library", Modifier.weight(1f), color = Color.White, fontSize = 24.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search", tint = Color.White) }
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "Create", tint = Color.White) }
                    DropdownMenu(addMenu, { addMenu = false }, containerColor = SpotifyColors.Elevated) {
                        DropdownMenuItem(text = { Text("Create a playlist") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                            onClick = { addMenu = false; creating = true })
                        DropdownMenuItem(text = { Text("Import a link or CSV") }, leadingIcon = { Icon(Icons.Outlined.Link, null) },
                            onClick = { addMenu = false; importing = true })
                    }
                }
            }
        }
        // Filter chips; tapping the selected one clears it, like in Spotify.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filter != null) Pill("✕", false) { filter = null }
            LibraryFilter.entries.forEach { option ->
                if (filter != null && filter != option) return@forEach
                if (!discovery.spotifyEnabled && option in setOf(LibraryFilter.Albums, LibraryFilter.Artists)) return@forEach
                val badge = when (option) {
                    LibraryFilter.YouTube -> MusicSource.YouTubeMusic; LibraryFilter.Local -> MusicSource.Local; else -> null
                }
                Pill(option.label, filter == option, badge = badge) {
                    filter = if (filter == option) null else option
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(Modifier.quietClick { sortMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sort.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(sortMenu, { sortMenu = false }, containerColor = SpotifyColors.Elevated) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label, color = if (option == sort) SpotifyColors.Green else Color.White) },
                            onClick = { sort = option; sortMenu = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (filter == LibraryFilter.YouTube && google != null) {
                Text(if (youTubeLoading) "Refreshing…" else "Refresh", color = SpotifyColors.TextSecondary, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.quietClick { model.refreshYouTubeLibrary() })
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding.dp)) {
            if (library.loading && content == null && discovery.spotifyEnabled) item { SpotifyLoader() }
            if (filter == LibraryFilter.YouTube && google == null) item {
                ConnectCard("Your YouTube Music playlists", "Sign in with Google to see, edit and play your YouTube Music playlists next to your Spotify ones.",
                    "Connect Google account") { navController.navigateSafely(fr.spotifeur.presentation.navigation.Screen.GoogleSignIn.route) }
            }
            if (showLiked) item(key = "liked") {
                CollectionRow(ui.likedSongs.copy(subtitle = "${content?.savedTrackCount ?: 0} songs"), onClick = { navController.openMusic(ui, ui.likedSongs) },
                    pinned = true)
            }
            if (filter == LibraryFilter.Local) {
                if (tracks.isEmpty()) item {
                    Text("No music files found on this phone. Allow access to audio files in Android settings.", Modifier.padding(16.dp),
                        color = SpotifyColors.TextSecondary, fontSize = 14.sp)
                }
                items(tracks, key = { it.id }) { track ->
                    TrackRow(track.title, track.subtitle, track.imageUrl, track.source, stable.currentSong?.id == track.id,
                        onClick = { model.play(tracks, tracks.indexOf(track)) }, onMore = { trackOptions = track })
                }
            }
            if (filter == LibraryFilter.Downloaded) {
                item {
                    Row(Modifier.fillMaxWidth().quietClick { navController.navigateSafely(SpotifireRoutes.DOWNLOADS) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FileDownload, null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text("Manage downloads", Modifier.weight(1f), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Progress, storage, quality", color = SpotifyColors.TextSecondary, fontSize = 12.sp)
                    }
                }
                val songs = downloads.values.filter { it.isComplete }.map { it.track }
                if (songs.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(16.dp).quietClick { ui.playSongsShuffled(songs.map { it.toSong() }, "Downloaded") },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FileDownload, null, tint = SpotifyColors.Green)
                        Spacer(Modifier.width(12.dp))
                        Text("Shuffle ${songs.size} downloaded songs", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
            items(collections, key = { it.id }) { item ->
                CollectionRow(item, onClick = { navController.openMusic(ui, item) }, onLongClick = { options = item },
                    pinned = pins.any { it.id == item.id })
            }
            if (collections.isEmpty() && !showLiked && filter != LibraryFilter.Local && filter != LibraryFilter.YouTube && !library.loading) item {
                Text("Nothing here yet.", Modifier.padding(16.dp), color = SpotifyColors.TextSecondary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ConnectCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(SpotifyColors.Elevated).padding(16.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(body, color = SpotifyColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
        Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color.White).quietClick(onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(action, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
