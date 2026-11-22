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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.MusicSource
import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.spotifire.SpotifireRoutes
import fr.spotifeur.spotifire.components.MediaCard
import fr.spotifeur.spotifire.components.Pill
import fr.spotifeur.spotifire.components.SectionTitle
import fr.spotifeur.spotifire.components.ShortcutTile
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import java.util.Calendar

/** Which service the Home shelves show. "All" is Spotify's own Home; the others are Spotifire's. */
enum class HomeFilter(val label: String) { All("All"), Spotify("Spotify"), YouTube("YouTube Music"), Local("On this phone") }

private fun Music.matches(filter: HomeFilter): Boolean = when (filter) {
    HomeFilter.All -> true
    HomeFilter.Spotify -> id.startsWith("spotify:")
    HomeFilter.YouTube -> source == MusicSource.YouTubeMusic || id.startsWith(fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX)
    HomeFilter.Local -> source == MusicSource.Local || id.startsWith(fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX)
}

@Composable
fun SpotifireHomeScreen(model: SpotifeurModel, navController: NavHostController, bottomPadding: Int) {
    val ui = model.ui
    val library by model.library.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val youTube by model.youTubePlaylists.collectAsStateWithLifecycle()
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val localPlaylists by model.localPlaylists.collectAsStateWithLifecycle()
    val localFiles by model.localTracks.collectAsStateWithLifecycle()
    val downloads by ui.downloadCollections.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(HomeFilter.All) }
    var options by remember { mutableStateOf<Music?>(null) }
    val content = library.content
    val multi = remember(localPlaylists) { localPlaylists.map { it.asMusic() } }
    val open: (Music) -> Unit = { item ->
        if (item.kind == MusicKind.Track) {
            val queue = recent.filter { it.kind == MusicKind.Track }
            val index = queue.indexOfFirst { it.id == item.id }
            if (index >= 0) model.play(queue, index) else model.play(listOf(item), 0)
        } else navController.openMusic(ui, item)
    }
    val playingContext = stable.currentSong?.let { ui.playerUiState.value.currentQueueSourceName }

    MusicOptionsHost(ui, navController, options) { options = null }

    // Shortcut grid: Liked Songs, pins, then what was recently opened.
    val shortcuts = remember(content, pins, recent, filter, multi, youTube) {
        buildList {
            if (filter == HomeFilter.All || filter == HomeFilter.Spotify) {
                if ((content?.savedTrackCount ?: 0) > 0) add(ui.likedSongs)
            }
            addAll(pins.filter { it.kind != MusicKind.Track && it.matches(filter) })
            addAll(recent.filter { it.kind != MusicKind.Track && it.matches(filter) })
            addAll((content?.playlists.orEmpty() + youTube + multi).filter { it.matches(filter) })
        }.distinctBy(Music::id).take(8)
    }
    val shelves: List<Pair<String, List<Music>>> = remember(content, recent, youTube, multi, filter, downloads, localFiles) {
        listOf(
            "Jump back in" to recent.filter { it.matches(filter) },
            "Your Spotify playlists" to content?.playlists.orEmpty().filter { filter == HomeFilter.All || filter == HomeFilter.Spotify },
            "Your YouTube Music playlists" to youTube.filter { filter == HomeFilter.All || filter == HomeFilter.YouTube },
            "Mixed playlists" to multi.filter { filter == HomeFilter.All || filter == HomeFilter.Local },
            "Your albums" to content?.albums.orEmpty().filter { it.matches(filter) },
            "Your artists" to content?.artists.orEmpty().filter { it.matches(filter) },
            "Downloaded" to downloads.values.filter { it.isComplete }.map { it.collection.item }.filter { it.matches(filter) },
            "On this phone" to localFiles.take(20).filter { filter == HomeFilter.All || filter == HomeFilter.Local },
        ).filter { it.second.isNotEmpty() }
    }

    Box(Modifier.fillMaxSize().background(SpotifyColors.Background)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = bottomPadding.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(model) { navController.navigateSafely(SpotifireRoutes.ACCOUNTS) }
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HomeFilter.entries.forEach { option ->
                            if (option == HomeFilter.Spotify && !discovery.spotifyEnabled) return@forEach
                            val badge = when (option) {
                                HomeFilter.Spotify -> MusicSource.Spotify
                                HomeFilter.YouTube -> MusicSource.YouTubeMusic
                                HomeFilter.Local -> MusicSource.Local
                                else -> null
                            }
                            Pill(option.label, filter == option, badge = badge) { filter = option }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(greeting(), Modifier.weight(1f), color = Color.White, fontSize = 24.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { navController.navigateSafely(SpotifireRoutes.DOWNLOADS) }) { Icon(Icons.Outlined.DownloadForOffline, "Downloads", tint = Color.White) }
                    IconButton(onClick = { navController.navigateSafely(Screen.History.route) }) { Icon(Icons.Outlined.History, "History", tint = Color.White) }
                    IconButton(onClick = { navController.navigateSafely(Screen.Settings.route) }) { Icon(Icons.Outlined.Settings, "Settings", tint = Color.White) }
                }
            }
            if (shortcuts.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shortcuts.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { item ->
                                ShortcutTile(item, Modifier.weight(1f), playing = playingContext == item.title,
                                    onClick = { open(item) }, onLongClick = { options = item })
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (library.loading && content == null) item { fr.spotifeur.spotifire.components.SpotifyLoader() }
            if (shelves.isEmpty() && !library.loading) item { EmptyHome(filter, google != null, discovery.spotifyEnabled, navController) }
            shelves.forEach { (title, items) ->
                item(key = "title-$title") { SectionTitle(title) }
                item(key = "row-$title") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(items.take(30), key = { it.id }) { music ->
                            MediaCard(music, onClick = {
                                if (music.kind == MusicKind.Track) {
                                    val tracks = items.filter { it.kind == MusicKind.Track }
                                    model.play(tracks, tracks.indexOfFirst { it.id == music.id }.coerceAtLeast(0))
                                } else open(music)
                            }, onLongClick = { options = music })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHome(filter: HomeFilter, googleConnected: Boolean, spotify: Boolean, navController: NavHostController) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val text = when {
            filter == HomeFilter.YouTube && !googleConnected -> "Connect your Google account to see your YouTube Music playlists here."
            filter == HomeFilter.Spotify && !spotify -> "Connect your Spotify account to see your library here."
            else -> "Play something and it will show up here. Use Search to find music on Spotify and YouTube Music."
        }
        Text(text, color = SpotifyColors.TextSecondary, fontSize = 14.sp)
        if ((filter == HomeFilter.YouTube && !googleConnected) || !spotify) {
            Spacer(Modifier.height(16.dp))
            Pill("Manage accounts", selected = true) { navController.navigateSafely(SpotifireRoutes.ACCOUNTS) }
        }
    }
}

/** The round profile picture at the top left; it opens the accounts page. */
@Composable
private fun Avatar(model: SpotifeurModel, onClick: () -> Unit) {
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val spotifyName by androidx.compose.runtime.produceState<String?>(null) {
        value = runCatching { fr.spotifeur.data.SpotifyAccount.current()?.username() }.getOrNull()
    }
    val initial = (spotifyName ?: google?.name)?.trim()?.firstOrNull()?.uppercase() ?: "S"
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFE8622C)).quietClick(onClick), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
