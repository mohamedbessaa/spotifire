package fr.spotifeur.spotifire.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Group
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.data.model.Song
import fr.spotifeur.data.model.toSong
import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.MusicOptionsHost
import fr.spotifeur.presentation.components.SongOptionsHost
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.navigation.openMusic
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.components.GreenPlayButton
import fr.spotifeur.spotifire.components.MediaCard
import fr.spotifeur.spotifire.components.PlaylistSourceBadge
import fr.spotifeur.spotifire.components.SectionTitle
import fr.spotifeur.spotifire.components.SpotifyLoader
import fr.spotifeur.spotifire.components.TrackRow
import fr.spotifeur.spotifire.components.isLikedSongs
import fr.spotifeur.spotifire.components.rememberArtworkColor
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Playlist page (Spotify, YouTube Music, mixed playlists, Liked Songs and radios)
// ---------------------------------------------------------------------------------------------

@Composable
fun SpotifirePlaylistScreen(model: SpotifeurModel, navController: NavHostController, playlistId: String, bottomPadding: Int) {
    val ui = model.ui
    val context = LocalContext.current
    val item = remember(playlistId) { ui.entity(playlistId) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val details by model.details.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val pins by model.pins.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val youTubeEditable by model.youTubeEditable.collectAsStateWithLifecycle()
    val youTubePlaylists by model.youTubePlaylists.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    val downloads by ui.downloadCollections.collectAsStateWithLifecycle()
    val preparing by ui.downloadPreparations.collectAsStateWithLifecycle()
    val trackDownloads by ui.downloads.collectAsStateWithLifecycle()
    val localFiles by model.localTracks.collectAsStateWithLifecycle()
    LaunchedEffect(playlistId) { model.loadDetail(item) }
    val detail = details[playlistId]
    val content = detail?.content
    val current = content?.item ?: item
    val tracks = remember(content?.tracks, localFiles) { model.withLocalFiles(content?.tracks.orEmpty()) }
    val songs = remember(tracks, saved) { tracks.map { it.toSong(it.id in saved) } }

    val isLiked = current.isLikedSongs()
    val isLocalList = playlistId.startsWith(LOCAL_PLAYLIST_PREFIX)
    val isYouTubeList = playlistId.startsWith(fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX)
    val ownsYouTube = isYouTubeList && google != null && (playlistId in youTubeEditable || model.youTubePlaylistOwned(current))
    val inSpotifyLibrary = discovery.spotifyEnabled && library.content?.playlists?.any { it.id == playlistId } == true
    val inYouTubeLibrary = isYouTubeList && youTubePlaylists.any { it.id == playlistId }
    var isOwned by remember(playlistId) { mutableStateOf(false) }
    LaunchedEffect(playlistId, current.ownerUsername, online) {
        if (!isLocalList && !isYouTubeList && !isLiked && online && SpotifyAccount.signedIn) {
            isOwned = runCatching {
                current.ownerUsername?.let { it == SpotifyAccount.require(context).username() } ?: model.spotifyPlaylists.isOwned(playlistId)
            }.getOrDefault(false)
        }
    }
    val editable = isLocalList || ownsYouTube || (isOwned && !isLiked)
    // Friends: a playlist edited together, or a Blend.
    val sharedViews by fr.spotifeur.social.SharedPlaylists.views.collectAsStateWithLifecycle()
    val blendViews by fr.spotifeur.social.Blends.views.collectAsStateWithLifecycle()
    val social by fr.spotifeur.social.Social.snapshot.collectAsStateWithLifecycle()
    val shared = sharedViews.firstOrNull { it.localId == playlistId }
    val blend = blendViews.firstOrNull { it.localId == playlistId }
    val addedBy = remember(shared) { shared?.entries?.associate { it.track.optString("id") to it.addedBy }.orEmpty() }
    var managing by remember { mutableStateOf(false) }
    if (managing && shared != null) fr.spotifeur.spotifire.friends.SharedMembersSheet(shared) { managing = false }
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf(false) }
    val removeAt: ((Int) -> Unit)? = when {
        isLocalList -> { index -> model.localPlaylistStore.removeAt(playlistId, index) }
        (isOwned && !isLiked || ownsYouTube) && online -> { index ->
            if (!removing) {
                removing = true
                scope.launch {
                    try { if (isYouTubeList) model.removeYouTubePlaylistTrack(current, index) else model.removeSpotifyPlaylistTrack(current, index) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { model.showMessage(failure.message.orEmpty()) }
                    finally { removing = false }
                }
            }
        }
        else -> null
    }

    var selected by remember { mutableStateOf<Song?>(null) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var options by remember { mutableStateOf<Music?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, songs.size, content?.nextPage) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged()
            .collect { last -> if (content?.nextPage != null && detail?.loading != true && last >= songs.size - 10) model.loadDetail(item, more = true) }
    }

    SongOptionsHost(ui, navController, selected, songs, current.title,
        onRemoveFromPlaylist = removeAt?.let { remove -> { remove(selectedIndex) } }) { selected = null }
    MusicOptionsHost(ui, navController, options) { options = null }
    if (renaming && isOwned) fr.spotifeur.presentation.components.SpotifyPlaylistEditor(model, playlist = current, onDismiss = { renaming = false })
    if (renaming && ownsYouTube) fr.spotifeur.presentation.components.YouTubePlaylistEditor(model, current) { renaming = false }
    if (renaming && isLocalList) fr.spotifeur.presentation.components.LocalPlaylistEditor(model, current) { renaming = false }
    if (adding) fr.spotifeur.presentation.components.AddTracksSheet(model, current,
        target = fr.spotifeur.data.playlistKindOf(current) ?: fr.spotifeur.data.PlaylistKind.MultiService,
        onAddLocal = { added -> model.localPlaylistStore.add(playlistId, added) },
        onAddRemote = { added -> if (isYouTubeList) model.addYouTubePlaylistTracks(current, added) else model.addSpotifyPlaylistTracks(current, added) },
        onDismiss = { adding = false })
    if (converting) fr.spotifeur.presentation.screens.ConvertTargetSheet(model, current, onDismiss = { converting = false }) { kind ->
        converting = false
        ui.register(current)
        navController.navigateSafely(Screen.Convert.createRoute(playlistId, kind.key))
    }
    if (deleting) fr.spotifeur.presentation.components.DeletePlaylistDialog(model, current, onDismiss = { deleting = false }) { navController.popBackStack() }

    val playingHere = stable.isPlaying && ui.playerUiState.value.currentQueueSourceName == current.title && songs.any { it.id == stable.currentSong?.id }
    val download = downloads[playlistId]
    val subtitle = buildString {
        append(when {
            isLiked -> "${content?.total ?: songs.size} songs"
            isYouTubeList -> "YouTube Music playlist"
            isLocalList -> "Mixed playlist • Spotify, YouTube Music and files"
            else -> current.subtitle
        })
    }

    CollectionPage(
        listState = listState,
        title = current.title,
        imageUrl = current.imageUrl,
        liked = isLiked,
        navController = navController,
        bottomPadding = bottomPadding,
        meta = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaylistSourceBadge(current)
                Spacer(Modifier.width(6.dp))
                Text(when {
                    blend != null -> "Blend • made for ${fr.spotifeur.social.Social.nameOf(blend.friend)} and you, new every day"
                    shared != null -> "Edited together • ${shared.members.size} people"
                    else -> subtitle
                }, color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (shared != null) Row(Modifier.padding(top = 8.dp).quietClick { managing = true }, verticalAlignment = Alignment.CenterVertically) {
                shared.members.take(5).forEachIndexed { i, key ->
                    fr.spotifeur.spotifire.friends.FriendAvatar(key, if (key == social.me) social.profile.name else fr.spotifeur.social.Social.nameOf(key), 24.dp,
                        Modifier.offset(x = (-6 * i).dp).border(1.5.dp, SpotifyColors.Background, androidx.compose.foundation.shape.CircleShape),
                        photo = if (key == social.me) social.profile.photo else social.photos[key])
                }
                Text(shared.members.filter { it != social.me }.joinToString(", ") { fr.spotifeur.social.Social.nameOf(it) }.let { "You, $it" },
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp))
            }
        },
        actions = {
            val inLibrary = isLiked || isLocalList || inSpotifyLibrary || inYouTubeLibrary || pins.any { it.id == playlistId }
            if (!isLiked && !isLocalList) IconButton(onClick = { ui.togglePin(current) }) {
                if (inLibrary) Icon(Icons.Filled.CheckCircle, "In library", tint = SpotifyColors.Green)
                else Icon(Icons.Outlined.AddCircleOutline, "Add to library", tint = SpotifyColors.TextSecondary)
            }
            IconButton(onClick = { ui.toggleDownloadItem(current) }) {
                DownloadButtonIcon(download, preparing = current.id in preparing)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = SpotifyColors.TextSecondary) }
                DropdownMenu(menu, { menu = false }, containerColor = SpotifyColors.Elevated) {
                    // A Blend is rebuilt every day from both tastes: nothing to add or rename by hand.
                    if ((editable && online || isLocalList) && blend == null) DropdownMenuItem(text = { Text("Add songs") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { menu = false; adding = true })
                    if (editable && blend == null) DropdownMenuItem(text = { Text("Edit details") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; renaming = true })
                    if (!isLiked && model.conversionTargets(current).isNotEmpty()) DropdownMenuItem(
                        text = { Text(if (isYouTubeList) "Convert to Spotify…" else if (isLocalList) "Convert to…" else "Convert to YouTube Music…") },
                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { menu = false; converting = true })
                    if (isLocalList && blend == null) DropdownMenuItem(text = { Text(if (shared != null) "Edit together…" else "Edit together with friends") },
                        leadingIcon = { Icon(Icons.Filled.Group, null) },
                        onClick = { menu = false; if (shared != null) managing = true else ui.pickFriends(current, fr.spotifeur.presentation.viewmodel.PlayerViewModel.FriendPick.EditTogether) })
                    if (songs.isNotEmpty()) DropdownMenuItem(text = { Text("Listen together") }, leadingIcon = { Icon(Icons.Filled.Headphones, null) },
                        onClick = { menu = false; ui.pickFriends(current, fr.spotifeur.presentation.viewmodel.PlayerViewModel.FriendPick.ListenTogether) })
                    if (blend != null) DropdownMenuItem(text = { Text("Stop the Blend") }, leadingIcon = { Icon(Icons.Filled.Close, null) },
                        onClick = { menu = false; fr.spotifeur.social.Social.launch { fr.spotifeur.social.Blends.stop(context, blend.id) } })
                    DropdownMenuItem(text = { Text("More options") }, leadingIcon = { Icon(Icons.Filled.MoreVert, null) },
                        onClick = { menu = false; options = current })
                    if (isLocalList || isOwned || ownsYouTube) DropdownMenuItem(text = { Text("Delete playlist", color = SpotifyColors.Error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = SpotifyColors.Error) }, onClick = { menu = false; deleting = true })
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (songs.isNotEmpty()) ui.playSongsShuffled(songs, current.title) }) {
                Icon(Icons.Outlined.Shuffle, "Shuffle", tint = SpotifyColors.TextSecondary, modifier = Modifier.size(28.dp))
            }
            GreenPlayButton(playingHere) {
                if (playingHere) ui.playPause() else if (songs.isNotEmpty()) ui.playSongs(songs, songs.first(), current.title)
            }
        },
    ) {
        if (detail?.loading == true && songs.isEmpty()) item { SpotifyLoader() }
        detail?.error?.let { error -> if (songs.isEmpty()) item { Text(error, Modifier.padding(16.dp), color = SpotifyColors.Error) } }
        if (songs.isEmpty() && detail?.loading != true && editable) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Let's find something for your playlist", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                fr.spotifeur.spotifire.components.Pill("Add to this playlist", selected = false) { adding = true }
            }
        }
        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
            TrackRow(
                title = song.title, subtitle = song.displayArtist, imageUrl = song.albumArtUriString, source = song.source,
                playing = stable.currentSong?.id == song.id, liked = !isLiked && song.isFavorite,
                downloaded = trackDownloads[song.id]?.isComplete == true,
                available = remember(song.id, online, trackDownloads[song.id]?.isComplete) { fr.spotifeur.spotifire.components.playableNow(context, song.id, online) },
                onClick = { ui.showAndPlaySong(song, songs, current.title, index) },
                onMore = { selected = song; selectedIndex = index },
                addedBy = addedBy[song.id]?.let { key -> {
                    fr.spotifeur.spotifire.friends.FriendAvatar(key, if (key == social.me) social.profile.name else fr.spotifeur.social.Social.nameOf(key), 15.dp,
                        photo = if (key == social.me) social.profile.photo else social.photos[key])
                } },
            )
        }
        if (detail?.loading == true && songs.isNotEmpty()) item { SpotifyLoader() }
    }
}

// ---------------------------------------------------------------------------------------------
// Album page
// ---------------------------------------------------------------------------------------------

@Composable
fun SpotifireAlbumScreen(model: SpotifeurModel, navController: NavHostController, albumId: Long, bottomPadding: Int) {
    val ui = model.ui
    val item = remember(albumId) { ui.entity(albumId) }
    if (item == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }
    val details by model.details.collectAsStateWithLifecycle()
    val saved by model.favorites.collectAsStateWithLifecycle()
    val stable by ui.stablePlayerState.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val downloads by ui.downloadCollections.collectAsStateWithLifecycle()
    val preparing by ui.downloadPreparations.collectAsStateWithLifecycle()
    val trackDownloads by ui.downloads.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(item.id) { model.loadDetail(item) }
    val detail = details[item.id]
    val content = detail?.content
    val current = content?.item ?: item
    val songs = remember(content?.tracks, saved) { content?.tracks.orEmpty().map { it.toSong(it.id in saved) } }
    var selected by remember { mutableStateOf<Song?>(null) }
    var options by remember { mutableStateOf<Music?>(null) }
    SongOptionsHost(ui, navController, selected, songs, current.title) { selected = null }
    MusicOptionsHost(ui, navController, options) { options = null }
    val inLibrary = current.id in saved || library.content?.albums?.any { it.id == current.id } == true
    val playingHere = stable.isPlaying && songs.any { it.id == stable.currentSong?.id }
    val download = downloads[current.id]
    val related = content?.related.orEmpty().filter { it.kind == MusicKind.Album && it.id != current.id }

    CollectionPage(
        listState = rememberLazyListState(),
        title = current.title,
        imageUrl = current.imageUrl,
        navController = navController,
        bottomPadding = bottomPadding,
        meta = {
            Column {
                Text(current.subtitle, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val year = current.note.takeIf { it.isNotBlank() }
                Text(listOfNotNull("Album", year).joinToString(" • "), color = SpotifyColors.TextSecondary, fontSize = 13.sp)
            }
        },
        actions = {
            IconButton(onClick = { ui.toggleSavedWithMessage(current) }) {
                if (inLibrary) Icon(Icons.Filled.CheckCircle, "Saved", tint = SpotifyColors.Green)
                else Icon(Icons.Outlined.AddCircleOutline, "Save", tint = SpotifyColors.TextSecondary)
            }
            IconButton(onClick = { ui.toggleDownloadItem(current) }) {
                DownloadButtonIcon(download, preparing = current.id in preparing)
            }
            IconButton(onClick = { options = current }) { Icon(Icons.Filled.MoreVert, "More", tint = SpotifyColors.TextSecondary) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (songs.isNotEmpty()) ui.playSongsShuffled(songs, current.title) }) {
                Icon(Icons.Outlined.Shuffle, "Shuffle", tint = SpotifyColors.TextSecondary, modifier = Modifier.size(28.dp))
            }
            GreenPlayButton(playingHere) {
                if (playingHere) ui.playPause() else if (songs.isNotEmpty()) ui.playSongs(songs, songs.first(), current.title)
            }
        },
    ) {
        if (detail?.loading == true && songs.isEmpty()) item { SpotifyLoader() }
        detail?.error?.let { error -> if (songs.isEmpty()) item { Text(error, Modifier.padding(16.dp), color = SpotifyColors.Error) } }
        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
            TrackRow(
                title = song.title, subtitle = song.displayArtist, imageUrl = null, source = song.source,
                playing = stable.currentSong?.id == song.id, liked = song.isFavorite, showArtwork = false, index = index + 1,
                downloaded = trackDownloads[song.id]?.isComplete == true,
                available = remember(song.id, online, trackDownloads[song.id]?.isComplete) { fr.spotifeur.spotifire.components.playableNow(context, song.id, online) },
                onClick = { ui.showAndPlaySong(song, songs, current.title, index) }, onMore = { selected = song },
            )
        }
        if (related.isNotEmpty()) {
            item { SectionTitle("More by ${current.subtitle.substringBefore(",")}") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    items(related, key = { it.id }) { album -> MediaCard(album, { navController.openMusic(ui, album) }, { options = album }) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared layout
// ---------------------------------------------------------------------------------------------

/**
 * The layout of Spotify's album and playlist pages: a gradient from the cover's color, the
 * cover in the middle, the title, a line of details, the action row with the big green play
 * button, then the tracks. The title fades into the top bar while scrolling.
 */
@Composable
fun CollectionPage(
    listState: LazyListState,
    title: String,
    imageUrl: String?,
    navController: NavHostController,
    bottomPadding: Int,
    liked: Boolean = false,
    meta: @Composable () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    body: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val tint = if (liked) Color(0xFF5038A0) else rememberArtworkColor(imageUrl, Color(0xFF404040))
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 } }
    Box(Modifier.fillMaxSize().background(SpotifyColors.Background)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(tint, SpotifyColors.Background))).statusBarsPadding()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Cover(imageUrl, Modifier.size(240.dp).shadow(24.dp), RoundedCornerShape(2.dp), liked = liked)
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(title, color = Color.White, fontSize = 24.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 28.sp)
                        Spacer(Modifier.height(8.dp))
                        meta()
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start, content = actions)
                }
            }
            body()
        }
        // Top bar: back arrow always; title and color once the header scrolled away.
        Row(
            Modifier.fillMaxWidth().background(if (collapsed) tint.copy(alpha = 1f) else Color.Transparent).statusBarsPadding().height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            if (collapsed) Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 16.dp))
        }
    }
}

/** The download button of album and playlist pages: arrow, progress ring with the percentage, or the green tick. */
@Composable
private fun DownloadButtonIcon(download: fr.spotifeur.playback.CollectionProgress?, preparing: Boolean) {
    when {
        download?.isComplete == true -> Icon(Icons.Filled.DownloadForOffline, "Downloaded, tap to remove", tint = SpotifyColors.Green, modifier = Modifier.size(28.dp))
        download != null && download.failed -> Icon(Icons.Outlined.ArrowCircleDown, "Download failed, tap to retry", tint = SpotifyColors.Error, modifier = Modifier.size(28.dp))
        download != null || preparing -> Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            val percent = download?.percent ?: 0
            if (download == null || percent == 0) CircularProgressIndicator(color = SpotifyColors.Green, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            else CircularProgressIndicator(progress = { percent / 100f }, color = if (download.paused) SpotifyColors.TextMuted else SpotifyColors.Green,
                trackColor = SpotifyColors.Highlight, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            if (percent > 0) Text("$percent", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        else -> Icon(Icons.Outlined.ArrowCircleDown, "Download", tint = SpotifyColors.TextSecondary, modifier = Modifier.size(28.dp))
    }
}
