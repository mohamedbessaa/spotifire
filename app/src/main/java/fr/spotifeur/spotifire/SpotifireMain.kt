package fr.spotifeur.spotifire

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.navigation.navigateToTopLevelSafely
import fr.spotifeur.presentation.viewmodel.PlayerSheetState
import fr.spotifeur.spotifire.components.SpotifyBottomArea
import fr.spotifeur.spotifire.screens.SpotifireAccountsScreen
import fr.spotifeur.spotifire.screens.SpotifireAlbumScreen
import fr.spotifeur.spotifire.screens.SpotifireArtistScreen
import fr.spotifeur.spotifire.screens.SpotifireHomeScreen
import fr.spotifeur.spotifire.screens.SpotifireLibraryScreen
import fr.spotifeur.spotifire.screens.SpotifireNowPlaying
import fr.spotifeur.spotifire.screens.SpotifirePlaylistScreen
import fr.spotifeur.spotifire.screens.SpotifireSearchScreen
import fr.spotifeur.spotifire.screens.SpotifireWelcomeScreen
import fr.spotifeur.spotifire.theme.SpotifyColors
import java.net.URLDecoder

/** Routes only Spotifire has. Every other route is Spotifeur's [Screen], so its screens still navigate correctly. */
object SpotifireRoutes {
    const val ACCOUNTS = "spotifire_accounts"
    const val DOWNLOADS = "spotifire_downloads"
}

/** Routes that look like settings pages: no tabs there, as in Spotify. */
private val routesWithoutTabs = setOf(
    Screen.Settings.route, Screen.Equalizer.route, Screen.SettingsCategory.route, Screen.Convert.route,
    Screen.GoogleSignIn.route, SpotifireRoutes.ACCOUNTS,
)

@Composable
fun SpotifireMain(model: SpotifeurModel) {
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val account by model.account.collectAsStateWithLifecycle()
    val guest by model.guest.collectAsStateWithLifecycle()
    // Signing in is optional: a guest uses YouTube Music, local files and mixed playlists.
    if (!discovery.spotifyEnabled && !guest) {
        if (account.restoring) Box(Modifier.fillMaxSize().background(Color.Black))
        else SpotifireWelcomeScreen(model, account)
        return
    }
    val ui = model.ui
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val sheet by ui.sheetState.collectAsStateWithLifecycle()
    val hasSong by remember { ui.derivedHasSong }.collectAsStateWithLifecycle()
    var typing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message by model.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.consumeMessage() } }
    LaunchedEffect(Unit) { ui.toastEvents.collect { snackbar.showSnackbar(it) } }
    // "Go to artist" / "Go to album" from the player and the track menus.
    LaunchedEffect(Unit) { ui.artistNavigationRequests.collect { ui.collapsePlayerSheet(); navController.navigateSafely(Screen.ArtistDetail.createRoute(it)) } }
    LaunchedEffect(Unit) { ui.albumNavigationRequests.collect { ui.collapsePlayerSheet(); navController.navigateSafely(Screen.AlbumDetail.createRoute(it)) } }

    val showTabs = route !in routesWithoutTabs && !(route == Screen.Search.route && typing)
    // Room left under lists for the mini player and the tabs.
    val bottomPadding = (if (showTabs) 72 else 16) + (if (hasSong) 72 else 0) + 24

    Box(Modifier.fillMaxSize().background(SpotifyColors.Background)) {
        SpotifireNavHost(model, navController, bottomPadding) { typing = it }
        val online by model.online.collectAsStateWithLifecycle()
        val offlineMode = ui.settings.collectAsStateWithLifecycle().value.offlineMode
        Box(Modifier.align(Alignment.BottomCenter)) {
            androidx.compose.foundation.layout.Column {
                if (!online && route != SpotifireRoutes.DOWNLOADS) OfflineBanner(offlineMode) { navController.navigateSafely(SpotifireRoutes.DOWNLOADS) }
                SpotifyBottomArea(ui, route, showTabs, onTab = { tab ->
                if (route == tab) navController.popBackStack(tab, inclusive = false) else navController.navigateToTopLevelSafely(tab)
            }, onOpenPlayer = { ui.expandPlayerSheet() })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = (bottomPadding - 16).dp)) { data ->
            Snackbar(containerColor = Color.White, contentColor = Color.Black) { Text(data.visuals.message) }
        }
        AnimatedVisibility(
            visible = sheet == PlayerSheetState.EXPANDED && hasSong,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(200)),
        ) {
            SpotifireNowPlaying(model, navController) { ui.collapsePlayerSheet() }
        }
    }
}

@Composable
private fun SpotifireNavHost(model: SpotifeurModel, navController: NavHostController, bottomPadding: Int, onTyping: (Boolean) -> Unit) {
    val defaultTab = remember { model.ui.settings.value.defaultTab }
    NavHost(
        navController = navController,
        startDestination = when (defaultTab) {
            AppSettings.TAB_SEARCH -> Screen.Search.route
            AppSettings.TAB_LIBRARY -> Screen.Library.route
            else -> Screen.Home.route
        },
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        // Spotify-style screens
        composable(Screen.Home.route) { SpotifireHomeScreen(model, navController, bottomPadding) }
        composable(Screen.Search.route) { SpotifireSearchScreen(model, navController, bottomPadding, onTyping) }
        composable(Screen.Library.route) { SpotifireLibraryScreen(model, navController, bottomPadding) }
        composable(SpotifireRoutes.ACCOUNTS) { SpotifireAccountsScreen(model, navController) }
        composable(SpotifireRoutes.DOWNLOADS) { fr.spotifeur.spotifire.screens.SpotifireDownloadsScreen(model, navController, bottomPadding) }
        composable(Screen.PlaylistDetail.route, arguments = listOf(navArgument("playlistId") { type = NavType.StringType })) { entry ->
            SpotifirePlaylistScreen(model, navController, URLDecoder.decode(entry.arguments?.getString("playlistId").orEmpty(), "UTF-8"), bottomPadding)
        }
        composable(Screen.AlbumDetail.route, arguments = listOf(navArgument("albumId") { type = NavType.LongType })) { entry ->
            SpotifireAlbumScreen(model, navController, entry.arguments?.getLong("albumId") ?: -1L, bottomPadding)
        }
        composable(Screen.ArtistDetail.route, arguments = listOf(navArgument("artistId") { type = NavType.LongType })) { entry ->
            SpotifireArtistScreen(model, navController, entry.arguments?.getLong("artistId") ?: -1L, bottomPadding)
        }
        // Spotifeur screens kept as they are, in the Spotify palette
        composable(Screen.Settings.route) { fr.spotifeur.presentation.screens.SettingsScreen(model, navController) }
        composable(Screen.History.route) { fr.spotifeur.presentation.screens.HistoryScreen(model, navController) }
        composable(Screen.GoogleSignIn.route) { fr.spotifeur.presentation.screens.GoogleSignInScreen(model, navController) }
        composable(Screen.Equalizer.route) { fr.spotifeur.presentation.screens.EqualizerScreen(navController = navController, playerViewModel = model.ui) }
        composable(Screen.SettingsCategory.route, arguments = listOf(navArgument("categoryId") { type = NavType.StringType })) { entry ->
            fr.spotifeur.presentation.screens.SettingsCategoryScreen(model, navController, entry.arguments?.getString("categoryId").orEmpty())
        }
        composable(
            Screen.Convert.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }, navArgument("target") { type = NavType.StringType }),
        ) { entry ->
            val id = URLDecoder.decode(entry.arguments?.getString("playlistId").orEmpty(), "UTF-8")
            fr.spotifeur.presentation.screens.ConvertScreen(model, navController, id, entry.arguments?.getString("target").orEmpty())
        }
    }
}

/** Shown while there is no connection (or offline mode is on): only downloaded and cached songs play. */
@Composable
private fun OfflineBanner(offlineMode: Boolean, onOpen: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(SpotifyColors.Highlight, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.CloudOff, null, tint = Color.White,
            modifier = Modifier.padding(end = 8.dp))
        Text(if (offlineMode) "Offline mode · playing downloads only" else "You're offline · playing downloads only",
            color = Color.White, modifier = Modifier.weight(1f))
        Text("Downloads", color = SpotifyColors.Green, modifier = Modifier.padding(start = 8.dp).then(
            Modifier.clickable(onClick = onOpen)))
    }
}
