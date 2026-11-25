package fr.spotifeur.spotifire.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.SearchSource
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.MusicSource
import fr.spotifeur.data.model.toSong
import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.spotifire.components.CollectionRow
import fr.spotifeur.spotifire.components.Pill
import fr.spotifeur.spotifire.components.SpotifyLoader
import fr.spotifeur.spotifire.components.TrackRow
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The colored "Browse all" tiles. Each one searches its genre across every source. */
private val Genres = listOf(
    "Pop" to Color(0xFF148A08), "Hip-Hop" to Color(0xFFBC5900), "Rock" to Color(0xFFE91429), "Rap" to Color(0xFF8D67AB),
    "Electronic" to Color(0xFF0D73EC), "R&B" to Color(0xFFDC148C), "Latin" to Color(0xFFE1118C), "Indie" to Color(0xFF608108),
    "Jazz" to Color(0xFF1E3264), "Chill" to Color(0xFF477D95), "Workout" to Color(0xFF777777), "Arabic" to Color(0xFFAF2896),
    "Rai" to Color(0xFF27856A), "Classical" to Color(0xFF7D4B32), "Afro" to Color(0xFFE8115B), "K-Pop" to Color(0xFF148A08),
    "Metal" to Color(0xFF503750), "Soul" to Color(0xFFBA5D07), "Focus" to Color(0xFF503750), "Sleep" to Color(0xFF1E3264),
)

@Composable
fun SpotifireSearchScreen(model: SpotifeurModel, navController: NavHostController, bottomPadding: Int, onTypingChange: (Boolean) -> Unit) {
    val ui = model.ui
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val history by ui.searchHistory.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf(discovery.query) }
    var focused by remember { mutableStateOf(false) }
    var trackOptions by remember { mutableStateOf<Music?>(null) }
    var options by remember { mutableStateOf<Music?>(null) }
    var importing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val searching = query.isNotBlank()
    // The tabs and mini player hide only while the keyboard is up, as in Spotify; results with the keyboard closed keep them.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    LaunchedEffect(focused && imeOpen) { onTypingChange(focused && imeOpen) }
    BackHandler(enabled = searching) { query = ""; model.search(""); focused = false }

    val open: (Music) -> Unit = { item ->
        ui.rememberSearchResult(item)
        if (item.kind == MusicKind.Track) {
            val tracks = discovery.results.filter { it.kind == MusicKind.Track }
            val index = tracks.indexOfFirst { it.id == item.id }
            if (index >= 0) model.play(tracks, index) else model.play(listOf(item), 0)
        } else navController.openMusic(ui, item)
    }
    LaunchedEffect(listState, discovery.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            if (searching && discovery.hasMore && !discovery.loading && last >= listState.layoutInfo.totalItemsCount - 6) model.searchNext()
        }
    }

    trackOptions?.let { item ->
        val song = item.toSong(item.id in saved)
        SongOptionsHost(ui, navController, song, listOf(song), query) { trackOptions = null }
    }
    MusicOptionsHost(ui, navController, options) { options = null }
    if (importing) ImportLinkDialog(model, navController) { importing = false }

    Column(Modifier.fillMaxSize().background(SpotifyColors.Background).statusBarsPadding()) {
        if (!searching && !focused) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", Modifier.weight(1f), color = Color.White, fontSize = 24.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
            IconButton(onClick = { importing = true }) { Icon(Icons.Outlined.Link, "Import a playlist link", tint = Color.White) }
        }
        // Spotify's white search box.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(46.dp).clip(RoundedCornerShape(6.dp))
                .background(if (focused || searching) SpotifyColors.Highlight else Color.White).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (focused || searching) Color.White else Color.Black
            Icon(Icons.Filled.Search, null, tint = ink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("What do you want to listen to?", color = if (focused) SpotifyColors.TextSecondary else Color(0xFF535353),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; model.search(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(SpotifyColors.Green),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); model.search(query, false) }),
                    modifier = Modifier.fillMaxWidth().onFocusChangedCompat { focused = it },
                )
            }
            if (query.isNotEmpty()) IconButton(onClick = { query = ""; model.search("") }) { Icon(Icons.Filled.Close, "Clear", tint = ink) }
        }
        if (searching) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val sources = buildList {
                add(SearchSource.All)
                if (discovery.spotifyEnabled) add(SearchSource.Spotify)
                add(SearchSource.YouTubeMusic); add(SearchSource.Local)
            }
            sources.forEach { source ->
                val label = when (source) {
                    SearchSource.All -> "All"; SearchSource.Spotify -> "Spotify"
                    SearchSource.YouTubeMusic -> "YouTube Music"; SearchSource.Local -> "On this phone"
                }
                val badge = when (source) {
                    SearchSource.Spotify -> MusicSource.Spotify; SearchSource.YouTubeMusic -> MusicSource.YouTubeMusic
                    SearchSource.Local -> MusicSource.Local; else -> null
                }
                Pill(label, discovery.activeSource == source, badge = badge) { model.setSearchSource(source) }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding.dp)) {
            when {
                searching -> {
                    if (discovery.results.isEmpty() && discovery.loading) item { SpotifyLoader() }
                    discovery.error?.let { error -> item { Text(error, Modifier.padding(16.dp), color = SpotifyColors.Error) } }
                    if (discovery.results.isEmpty() && !discovery.loading && discovery.error == null) item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Couldn't find \"$query\"", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Try searching again using a different spelling or keyword.", color = SpotifyColors.TextSecondary, fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    items(discovery.results, key = { it.kind.name + it.id }) { item -> ResultRow(item, stable.currentSong?.id, saved, open, { trackOptions = it }, { options = it }) }
                    if (discovery.loading && discovery.results.isNotEmpty()) item { SpotifyLoader() }
                }
                focused -> {
                    if (history.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Recent searches", Modifier.weight(1f), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("Clear", color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.quietClick { ui.clearSearchHistory() })
                            }
                        }
                        items(history, key = { "h" + it.id }) { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { ResultRow(item, stable.currentSong?.id, saved, open, { trackOptions = it }, { options = it }) }
                                IconButton(onClick = { ui.forgetSearch(item) }) { Icon(Icons.Filled.Close, "Remove", tint = SpotifyColors.TextSecondary) }
                            }
                        }
                    } else item {
                        Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Play what you love", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Search Spotify, YouTube Music and the music on your phone.", color = SpotifyColors.TextSecondary, fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp))
                        }
                    }
                }
                else -> {
                    item { Text("Browse all", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    items(Genres.chunked(2)) { pair ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { (name, color) ->
                                GenreTile(name, color, Modifier.weight(1f)) { query = name; model.search(name, false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(item: Music, playingId: String?, saved: Set<String>, open: (Music) -> Unit, onTrackMore: (Music) -> Unit, onMore: (Music) -> Unit) {
    if (item.kind == MusicKind.Track) {
        TrackRow(
            title = item.title, subtitle = "Song • " + item.subtitle, imageUrl = item.imageUrl, source = item.source,
            playing = item.id == playingId, liked = item.id in saved, onClick = { open(item) }, onMore = { onTrackMore(item) },
        )
    } else CollectionRow(item, onClick = { open(item) }, onLongClick = { onMore(item) })
}

@Composable
private fun GenreTile(name: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    // The genre's colour with a slight tone shift down to its corner; no placeholder shape pretending to be a cover.
    Box(modifier.height(96.dp).clip(RoundedCornerShape(8.dp))
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(color, androidx.compose.ui.graphics.lerp(color, Color.Black, 0.22f))))
        .quietClick(onClick)) {
        Text(name, Modifier.padding(16.dp), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** Spotifeur's import: a Spotify, YouTube or YouTube Music playlist link becomes a playlist in the library. */
@Composable
private fun ImportLinkDialog(model: SpotifeurModel, navController: NavHostController, onDismiss: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = SpotifyColors.Elevated,
        title = { Text("Import a playlist", color = Color.White) },
        text = {
            Column {
                Text("Paste a Spotify, YouTube or YouTube Music playlist link.", color = SpotifyColors.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(value = link, onValueChange = { link = it }, singleLine = true, enabled = !working,
                    placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = SpotifyColors.Error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = link.isNotBlank() && !working, onClick = {
                working = true; error = null
                scope.launch {
                    try {
                        val playlist = model.importLink(link.trim())
                        onDismiss(); navController.openMusic(model.ui, playlist)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "This link could not be imported." }
                    finally { working = false }
                }
            }) { Text(if (working) "Importing…" else "Import", color = SpotifyColors.Green) }
        },
        dismissButton = { androidx.compose.material3.TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}

private fun Modifier.onFocusChangedCompat(onChange: (Boolean) -> Unit): Modifier =
    this.then(Modifier.onFocusChanged { onChange(it.isFocused) })
