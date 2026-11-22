package fr.spotifeur.spotifire.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.R
import fr.spotifeur.data.MusicSource
import fr.spotifeur.presentation.viewmodel.PlayerViewModel
import fr.spotifeur.spotifire.theme.SpotifyColors

/** Spotify's floating mini player: tinted by the artwork, with a thin progress line at the bottom. */
@Composable
fun SpotifyMiniPlayer(ui: PlayerViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by ui.stablePlayerState.collectAsStateWithLifecycle()
    val song = state.currentSong ?: return
    val position by ui.currentPlaybackPosition.collectAsStateWithLifecycle()
    val favorite by ui.isCurrentSongFavorite.collectAsStateWithLifecycle()
    val tint by animateColorAsState(rememberArtworkColor(song.albumArtUriString), tween(600), label = "mini")
    val progress = if (state.totalDuration > 0) (position.toFloat() / state.totalDuration).coerceIn(0f, 1f) else 0f
    Column(
        modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(tint)
            .quietClick(onOpen)
            .pointerInput(song.id) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, amount -> drag += amount },
                    onDragEnd = { if (drag < -120f) ui.nextSong() else if (drag > 120f) ui.previousSong() },
                )
            },
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.albumArtUriString, Modifier.size(40.dp), RoundedCornerShape(4.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.source != MusicSource.Spotify) { SourceBadge(song.source, size = 12.dp); Spacer(Modifier.width(4.dp)) }
                    Text(song.displayArtist, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.size(40.dp).quietClick { ui.toggleFavorite() }, contentAlignment = Alignment.Center) {
                if (favorite) Icon(Icons.Filled.CheckCircle, "Saved", tint = SpotifyColors.Green, modifier = Modifier.size(24.dp))
                else Icon(painterResource(R.drawable.ic_add), "Save", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Box(Modifier.size(44.dp).quietClick { ui.playPause() }, contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (state.isPlaying) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                    if (state.isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(26.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White))
        }
    }
}

data class SpotifyTab(val route: String, val label: String, val selectedIcon: ImageVector, val icon: ImageVector)

val SpotifyTabs = listOf(
    SpotifyTab(fr.spotifeur.presentation.navigation.Screen.Home.route, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    SpotifyTab(fr.spotifeur.presentation.navigation.Screen.Search.route, "Search", Icons.Filled.Search, Icons.Outlined.Search),
    SpotifyTab(fr.spotifeur.presentation.navigation.Screen.Library.route, "Your Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
)

/** Bottom area: mini player above the three tabs, on Spotify's black fade. */
@Composable
fun SpotifyBottomArea(
    ui: PlayerViewModel,
    currentRoute: String?,
    showTabs: Boolean,
    onTab: (String) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(
            remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f), Color.Black)) }
        ).navigationBarsPadding()
    ) {
        Spacer(Modifier.height(10.dp))
        SpotifyMiniPlayer(ui, onOpenPlayer)
        if (showTabs) Row(Modifier.fillMaxWidth().height(60.dp).padding(top = 4.dp)) {
            SpotifyTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Column(
                    Modifier.weight(1f).fillMaxHeight().quietClick { onTab(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(6.dp))
                    Icon(if (selected) tab.selectedIcon else tab.icon, tab.label, tint = if (selected) Color.White else SpotifyColors.TextSecondary,
                        modifier = Modifier.size(26.dp))
                    Text(tab.label, color = if (selected) Color.White else SpotifyColors.TextSecondary, fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
                }
            }
        } else Spacer(Modifier.height(8.dp))
    }
}
