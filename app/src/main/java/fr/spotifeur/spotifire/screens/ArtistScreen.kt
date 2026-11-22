package fr.spotifeur.spotifire.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toSong
import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.GreenPlayButton
import fr.spotifeur.spotifire.components.MediaCard
import fr.spotifeur.spotifire.components.SectionTitle
import fr.spotifeur.spotifire.components.SpotifyLoader
import fr.spotifeur.spotifire.components.TrackRow
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.components.rememberArtworkColor
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import java.text.NumberFormat

@Composable
fun SpotifireArtistScreen(model: SpotifeurModel, navController: NavHostController, artistId: Long, bottomPadding: Int) {
    val ui = model.ui
    val item = remember(artistId) { ui.entity(artistId) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val details by model.details.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    LaunchedEffect(item.id) { model.loadDetail(item) }
    val detail = details[item.id]
    val content = detail?.content
    val current = content?.item ?: item
    val profile = content?.artist
    val songs = remember(content?.tracks, saved) { content?.tracks.orEmpty().map { it.toSong(it.id in saved) } }
    val related = content?.related.orEmpty().filter { it.id != current.id }
    val byId = remember(related) { related.associateBy(Music::id) }
    val others = related.filter { it.kind == MusicKind.Artist }
    var showAllPopular by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Song?>(null) }
    var options by remember { mutableStateOf<Music?>(null) }
    SongOptionsHost(ui, navController, selected, songs, current.title) { selected = null }
    MusicOptionsHost(ui, navController, options) { options = null }
    val following = current.id in saved || library.content?.artists?.any { it.id == current.id } == true
    val playingHere = stable.isPlaying && songs.any { it.id == stable.currentSong?.id }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val compact = remember { android.icu.text.CompactDecimalFormat.getInstance(java.util.Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT) }
    val tint = rememberArtworkColor(current.imageUrl, Color(0xFF404040))
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    // Release shelves in Spotify's order; YouTube Music artists only have the generic "related" list.
    val shelves: List<Pair<String, List<Music>>> = remember(profile, related) {
        if (profile == null) listOf("Discography" to related.filter { it.kind == MusicKind.Album })
        else listOf(
            "Popular releases" to profile.popularReleases, "Albums" to profile.albums, "Singles and EPs" to profile.singles,
            "Compilations" to profile.compilations, "Appears on" to profile.appearsOn,
        ).map { (title, ids) -> title to ids.mapNotNull(byId::get) }
    }.filter { it.second.isNotEmpty() }

    Box(Modifier.fillMaxSize().background(SpotifyColors.Background)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    Cover(profile?.headerImageUrl ?: current.imageUrl, Modifier.fillMaxSize(), RectangleShape)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.15f), SpotifyColors.Background))))
                    Text(current.title, Modifier.align(Alignment.BottomStart).padding(16.dp), color = Color.White, fontSize = 44.sp,
                        fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 46.sp)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    profile?.monthlyListeners?.let {
                        val text = if (profile.approximate) compact.format(it) else numbers.format(it)
                        Text("$text monthly listeners", color = SpotifyColors.TextSecondary, fontSize = 13.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).border(1.dp, if (following) Color.White else SpotifyColors.TextMuted, RoundedCornerShape(50))
                                .quietClick { ui.toggleSavedWithMessage(current) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        ) { Text(if (following) "Following" else "Follow", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        IconButton(onClick = { options = current }) { Icon(Icons.Filled.MoreVert, "More", tint = SpotifyColors.TextSecondary) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { if (songs.isNotEmpty()) ui.playSongsShuffled(songs, current.title) }) {
                            Icon(Icons.Outlined.Shuffle, "Shuffle", tint = SpotifyColors.TextSecondary, modifier = Modifier.size(28.dp))
                        }
                        GreenPlayButton(playingHere) {
                            if (playingHere) ui.playPause() else if (songs.isNotEmpty()) ui.playSongs(songs, songs.first(), current.title)
                        }
                    }
                }
            }
            if (detail?.loading == true && songs.isEmpty()) item { SpotifyLoader() }
            if (songs.isNotEmpty()) item { SectionTitle("Popular") }
            itemsIndexed(if (showAllPopular) songs else songs.take(5), key = { index, song -> "p$index-${song.id}" }) { index, song ->
                val plays = profile?.playCounts?.get(song.id)?.let { if (profile.approximate) compact.format(it) else numbers.format(it) }
                TrackRow(
                    title = song.title, subtitle = plays ?: song.displayArtist, imageUrl = song.albumArtUriString, source = song.source,
                    playing = stable.currentSong?.id == song.id, liked = song.isFavorite, index = index + 1,
                    onClick = { ui.playSongs(songs, song, current.title) }, onMore = { selected = song },
                )
            }
            if (songs.size > 5) item {
                Text(if (showAllPopular) "Show less" else "See more", Modifier.padding(16.dp).quietClick { showAllPopular = !showAllPopular },
                    color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            shelves.forEach { (title, items) ->
                item(key = "t$title") { SectionTitle(title) }
                item(key = "r$title") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(items, key = { it.id }) { album -> MediaCard(album, { navController.openMusic(ui, album) }, { options = album }) }
                    }
                }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle("Fans also like") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(others, key = { it.id }) { artist -> MediaCard(artist, { navController.openMusic(ui, artist) }, { options = artist }) }
                    }
                }
            }
            val biography = profile?.biography?.takeIf(String::isNotBlank)
            if (biography != null) item {
                SectionTitle("About")
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Elevated)) {
                    profile.headerImageUrl?.let { Cover(it, Modifier.fillMaxWidth().height(200.dp), RectangleShape) }
                    Column(Modifier.padding(16.dp)) {
                        profile.worldRank?.let { Text("#$it in the world", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        var open by remember { mutableStateOf(false) }
                        Text(biography, color = SpotifyColors.TextSecondary, fontSize = 14.sp, maxLines = if (open) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).quietClick { open = !open })
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(if (collapsed) tint else Color.Transparent).statusBarsPadding().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = if (collapsed) 0f else 0.4f)),
                    contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            }
            if (collapsed) Text(current.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
