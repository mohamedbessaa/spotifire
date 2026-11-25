package fr.spotifeur.spotifire.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.MusicSource
import fr.spotifeur.data.source
import fr.spotifeur.presentation.components.SmartImage
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clickable with no ripple, like most taps in the Spotify app, but never dead: the element sinks 3% while pressed,
 * Spotify's own touch feedback, and springs back in 120 ms.
 */
fun Modifier.quietClick(onClick: () -> Unit): Modifier = this.composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.97f else 1f,
        androidx.compose.animation.core.tween(120), label = "press")
    Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

/** Artwork with Spotify's dark placeholder. Artists are round, everything else is square. */
@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp), liked: Boolean = false) {
    if (liked) {
        LikedSongsArt(modifier.clip(shape))
        return
    }
    Box(modifier.clip(shape).background(SpotifyColors.Highlight), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Filled.MusicNote, null, tint = SpotifyColors.TextMuted, modifier = Modifier.fillMaxSize(0.45f))
        } else {
            SmartImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), shape = shape)
        }
    }
}

/** The purple-to-white gradient tile with a heart, as for Liked Songs in Spotify. */
@Composable
fun LikedSongsArt(modifier: Modifier = Modifier) {
    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFF8E8EE5), Color(0xFFC4EFD9)))),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f)) }
}

fun Music.isLikedSongs(): Boolean = id == SpotifeurModel.LIKED_SONGS

fun coverShape(kind: MusicKind, corner: Dp = 4.dp): Shape = if (kind == MusicKind.Artist) CircleShape else RoundedCornerShape(corner)

/**
 * Small badge telling where a track or playlist comes from. Spotify does not need one in the
 * official app, but here YouTube Music and local files sit in the same lists.
 */
@Composable
fun SourceBadge(source: MusicSource, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    when (source) {
        MusicSource.Spotify -> Icon(painterResource(R.drawable.ic_source_spotify), "Spotify", modifier.size(size), tint = SpotifyColors.Green)
        MusicSource.YouTubeMusic -> Icon(painterResource(R.drawable.ic_source_youtube_music), "YouTube Music", modifier.size(size), tint = SpotifyColors.YouTubeRed)
        MusicSource.Local -> Icon(Icons.Filled.Folder, "Local file", modifier.size(size), tint = SpotifyColors.LocalBlue)
    }
}

/** Badge for a whole playlist: multi-service ones get both marks. */
@Composable
fun PlaylistSourceBadge(item: Music, modifier: Modifier = Modifier) {
    when {
        item.id.startsWith(fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX) -> SourceBadge(MusicSource.YouTubeMusic, modifier)
        item.id.startsWith(fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX) -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SourceBadge(MusicSource.Spotify, size = 12.dp); SourceBadge(MusicSource.YouTubeMusic, size = 12.dp)
        }
        item.id.startsWith("spotify:") -> SourceBadge(MusicSource.Spotify, modifier)
        else -> SourceBadge(item.source, modifier)
    }
}

/** A Spotify pill: green when selected, dark grey otherwise. */
@Composable
fun Pill(label: String, selected: Boolean = false, modifier: Modifier = Modifier, badge: MusicSource? = null, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) SpotifyColors.Green else SpotifyColors.Highlight)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (badge != null && !selected) SourceBadge(badge)
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = Color.White, fontSize = 22.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Text(action, color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.quietClick(onAction))
        }
    }
}

/** A carousel card: 150 dp artwork, title and one line of subtitle. */
@Composable
fun MediaCard(item: Music, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, size: Dp = 150.dp) {
    val artist = item.kind == MusicKind.Artist
    Column(
        Modifier.width(size + 12.dp).padding(6.dp).pressable(onClick, onLongClick),
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Cover(item.imageUrl, Modifier.size(size), coverShape(item.kind, 6.dp), liked = item.isLikedSongs())
        Spacer(Modifier.height(8.dp))
        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = if (artist) 1 else 2,
            overflow = TextOverflow.Ellipsis, textAlign = if (artist) TextAlign.Center else TextAlign.Start)
        val subtitle = if (artist) "Artist" else item.subtitle
        if (subtitle.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.kind == MusicKind.Playlist) { PlaylistSourceBadge(item); Spacer(Modifier.width(4.dp)) }
            Text(subtitle, color = SpotifyColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One of the two-column shortcut tiles at the top of Spotify's Home. */
@Composable
fun ShortcutTile(item: Music, modifier: Modifier = Modifier, playing: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.Highlight).pressable(onClick, onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.imageUrl, Modifier.size(56.dp), RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp), liked = item.isLikedSongs())
        Text(item.title, Modifier.weight(1f).padding(horizontal = 8.dp), color = if (playing) SpotifyColors.Green else Color.White,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
    }
}

/**
 * A track row in a list: artwork (or number), title in green while it plays, source badge,
 * artists, and the ⋮ button. Long-press opens the same options as ⋮.
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    source: MusicSource,
    playing: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    downloaded: Boolean = false,
    liked: Boolean = false,
    showArtwork: Boolean = true,
    available: Boolean = true,
    /** Shared playlists: the avatar of the friend who added the song. */
    addedBy: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().alpha(if (available) 1f else 0.4f).pressable(onClick, onMore).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null && !showArtwork) {
            Text("$index", Modifier.width(28.dp), color = if (playing) SpotifyColors.Green else SpotifyColors.TextSecondary, fontSize = 15.sp)
        }
        if (showArtwork) {
            Cover(imageUrl, Modifier.size(48.dp), RoundedCornerShape(2.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (playing) SpotifyColors.Green else Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (liked) { Icon(Icons.Filled.CheckCircle, null, tint = SpotifyColors.Green, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                if (downloaded) { Icon(Icons.Filled.Download, null, tint = SpotifyColors.Green, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                addedBy?.let { it(); Spacer(Modifier.width(5.dp)) }
                if (source != MusicSource.Spotify) { SourceBadge(source, size = 13.dp); Spacer(Modifier.width(4.dp)) }
                Text(subtitle, color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, "More", tint = SpotifyColors.TextSecondary) }
    }
}

/** A row in Library or Search results for an album, artist or playlist. */
@Composable
fun CollectionRow(item: Music, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, pinned: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().pressable(onClick, onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.imageUrl, Modifier.size(64.dp), coverShape(item.kind, 4.dp), liked = item.isLikedSongs())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) { Icon(Icons.Filled.PushPin, null, tint = SpotifyColors.Green, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                if (item.kind == MusicKind.Playlist) { PlaylistSourceBadge(item); Spacer(Modifier.width(4.dp)) }
                Text(kindLabel(item), color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun kindLabel(item: Music): String = when (item.kind) {
    MusicKind.Artist -> "Artist"
    MusicKind.Album -> listOf("Album", item.subtitle).filter(String::isNotBlank).joinToString(" • ")
    MusicKind.Playlist, MusicKind.Radio -> listOf("Playlist", item.subtitle).filter(String::isNotBlank).joinToString(" • ")
    MusicKind.Track -> listOf("Song", item.subtitle).filter(String::isNotBlank).joinToString(" • ")
}

@Composable
fun SpotifyLoader(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SpotifyColors.Green, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

/** The big round green play button of Spotify's collection pages. */
@Composable
fun GreenPlayButton(playing: Boolean, modifier: Modifier = Modifier, size: Dp = 56.dp, onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(SpotifyColors.Green).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com), if (playing) "Pause" else "Play",
            tint = Color.Black, modifier = Modifier.size(size * 0.45f),
        )
    }
}

/**
 * The dominant dark color of an artwork, used for the gradient behind headers, the mini
 * player and the now-playing screen, the way Spotify tints them.
 */
@Composable
fun rememberArtworkColor(url: String?, fallback: Color = SpotifyColors.Highlight): Color {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(ArtworkColors.cached(url) ?: fallback) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        ArtworkColors.cached(url)?.let { color = it; return@LaunchedEffect }
        color = withContext(Dispatchers.IO) { ArtworkColors.extract(context, url) } ?: fallback
    }
    return color
}

internal object ArtworkColors {
    private val cache = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 200
    }

    @Synchronized fun cached(url: String?): Color? = url?.let { cache[it] }

    suspend fun extract(context: android.content.Context, url: String): Color? = runCatching {
        val request = coil.request.ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
        val result = coil.Coil.imageLoader(context).execute(request)
        val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
        val palette = androidx.palette.graphics.Palette.from(bitmap).generate()
        val argb = palette.darkVibrantSwatch?.rgb ?: palette.vibrantSwatch?.rgb ?: palette.darkMutedSwatch?.rgb
            ?: palette.dominantSwatch?.rgb ?: return null
        // Keep it dark enough for white text, like Spotify does.
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
        hsv[2] = hsv[2].coerceAtMost(0.55f)
        Color(android.graphics.Color.HSVToColor(hsv)).also { synchronized(this) { cache[url] = it } }
    }.getOrNull()
}

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Whether a track can play right now: always online; offline only when its whole audio is
 * on the phone (downloaded, or still complete in the recent-audio cache), or it is a local file.
 */
fun playableNow(context: android.content.Context, trackId: String, online: Boolean): Boolean =
    online || fr.spotifeur.data.isLocalTrackId(trackId) || fr.spotifeur.playback.AudioCache.isAvailableOffline(context, trackId)

/**
 * The round profile picture at the top left of Home and Your Library; it opens the accounts page. It shows the Spotify
 * profile picture, else the first letter of the Spotify display name, else of the Google account's name.
 */
@Composable
fun ProfileAvatar(model: fr.spotifeur.SpotifeurModel, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val profile by androidx.compose.runtime.produceState<fr.spotifeur.data.SpotifyProfile?>(null, discovery.spotifyEnabled) {
        value = if (discovery.spotifyEnabled) fr.spotifeur.data.SpotifyProfiles.current() else null
    }
    val picture = profile?.imageUrl ?: google?.photoUrl
    val initial = (profile?.name ?: google?.name)?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "S"
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFE8622C)).quietClick(onClick), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.Black, fontSize = (size.value * 0.44f).sp, fontWeight = FontWeight.Bold)
        if (picture != null) Cover(picture, Modifier.fillMaxSize(), CircleShape)
    }
}

/**
 * A track's Canvas: its looping video, muted, filling the screen behind the player like in Spotify. Canvases are
 * portrait 9:16; the video keeps that ratio and is cropped to the screen rather than stretched.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun CanvasVideo(url: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            prepare(); playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { android.view.TextureView(it).also(player::setVideoTextureView) },
            modifier = Modifier.fillMaxHeight().aspectRatioMatchHeight(9f / 16f),
        )
    }
}

private fun Modifier.aspectRatioMatchHeight(ratio: Float): Modifier =
    this.then(Modifier.wrapContentWidth(unbounded = true).aspectRatio(ratio, matchHeightConstraintsFirst = true))
