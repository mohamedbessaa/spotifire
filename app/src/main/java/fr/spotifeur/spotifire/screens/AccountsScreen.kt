package fr.spotifeur.spotifire.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.AccountUi
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.MusicSource
import fr.spotifeur.data.SpotifyAccount
import fr.spotifeur.presentation.navigation.Screen
import fr.spotifeur.presentation.navigation.navigateSafely
import fr.spotifeur.presentation.screens.SPOTIFY_GOOGLE_REDIRECT
import fr.spotifeur.presentation.screens.SpotifyGoogleSignIn
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.SourceBadge
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle

// ---------------------------------------------------------------------------------------------
// Accounts: the page that does not exist in Spotify, where both services are connected.
// ---------------------------------------------------------------------------------------------

@Composable
fun SpotifireAccountsScreen(model: SpotifeurModel, navController: NavHostController) {
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val google by model.youTubeAccount.collectAsStateWithLifecycle()
    val youTube by model.youTubePlaylists.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val localFiles by model.localTracks.collectAsStateWithLifecycle()
    // The display name, never the username: an email account's username is a random id.
    val spotifyProfile by produceState<fr.spotifeur.data.SpotifyProfile?>(null, discovery.spotifyEnabled) {
        value = if (discovery.spotifyEnabled) fr.spotifeur.data.SpotifyProfiles.current() else null
    }
    val spotifyName = spotifyProfile?.name
    var confirmSpotify by remember { mutableStateOf(false) }
    var confirmGoogle by remember { mutableStateOf(false) }

    if (confirmSpotify) ConfirmDialog("Log out of Spotify?", "Your Spotify library leaves this phone. Mixed playlists and YouTube Music stay.",
        "Log out", { confirmSpotify = false }) { confirmSpotify = false; model.disconnectSpotify() }
    if (confirmGoogle) ConfirmDialog("Disconnect Google?", "Your YouTube Music playlists stay on your Google account. YouTube Music search and playback keep working.",
        "Disconnect", { confirmGoogle = false }) { confirmGoogle = false; model.disconnectGoogle() }

    Column(Modifier.fillMaxSize().background(SpotifyColors.Background).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Accounts", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text("Spotifire puts your Spotify and YouTube Music libraries side by side. Each account is optional.",
            Modifier.padding(horizontal = 16.dp), color = SpotifyColors.TextSecondary, fontSize = 14.sp)

        AccountCard(
            source = MusicSource.Spotify,
            name = "Spotify",
            connected = discovery.spotifyEnabled,
            detail = if (discovery.spotifyEnabled) listOfNotNull(spotifyName, library.content?.let { "${it.playlists.size} playlists • ${it.savedTrackCount} liked songs" })
                .joinToString("\n") else "Your playlists, liked songs, albums and artists, synced both ways. Plays with Spotify's own audio engine.",
            action = if (discovery.spotifyEnabled) "Log out" else "Log in",
            photo = spotifyProfile?.imageUrl,
            onAction = { if (discovery.spotifyEnabled) confirmSpotify = true else model.showSignIn() },
        )
        AccountCard(
            source = MusicSource.YouTubeMusic,
            name = "YouTube Music",
            connected = google != null,
            photo = google?.photoUrl,
            detail = if (google != null) listOfNotNull(google?.name, google?.handle?.takeIf(String::isNotBlank), "${youTube.size} playlists").joinToString("\n")
                else "Search and play works without an account. Connect Google to see, create and edit your YouTube Music playlists.",
            action = if (google != null) "Disconnect" else "Connect Google",
            onAction = { if (google != null) confirmGoogle = true else navController.navigateSafely(Screen.GoogleSignIn.route) },
        )
        AccountCard(
            source = MusicSource.Local,
            name = "Music on this phone",
            connected = localFiles.isNotEmpty(),
            detail = if (localFiles.isNotEmpty()) "${localFiles.size} songs found" else "Allow access to audio files to play the music stored on your phone.",
            action = "Rescan",
            onAction = { model.refreshLocalTracks() },
        )

        Spacer(Modifier.height(16.dp))
        MenuRow(Icons.Outlined.Settings, "Settings and privacy") { navController.navigateSafely(Screen.Settings.route) }
        MenuRow(Icons.Outlined.History, "Listening history") { navController.navigateSafely(Screen.History.route) }
        MenuRow(Icons.Outlined.Equalizer, "Equalizer") { navController.navigateSafely(Screen.Equalizer.route) }
        MenuRow(Icons.Outlined.DownloadForOffline, "Downloads") { navController.navigateSafely(fr.spotifeur.spotifire.SpotifireRoutes.DOWNLOADS) }
        MenuRow(Icons.Outlined.Folder, "Storage and cache") { navController.navigateSafely(Screen.SettingsCategory.createRoute("storage")) }
        Spacer(Modifier.height(140.dp))
    }
}

@Composable
private fun AccountCard(source: MusicSource, name: String, connected: Boolean, detail: String, action: String, photo: String? = null, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Elevated).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photo != null) Cover(photo, Modifier.size(36.dp), CircleShape)
            else Box(Modifier.size(36.dp).clip(CircleShape).background(SpotifyColors.Highlight), contentAlignment = Alignment.Center) { SourceBadge(source, size = 20.dp) }
            Spacer(Modifier.width(12.dp))
            Text(name, Modifier.weight(1f), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(if (connected) "Connected" else "Not connected", color = if (connected) SpotifyColors.Green else SpotifyColors.TextMuted, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
        }
        Text(detail, color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .then(if (connected) Modifier.border(1.dp, SpotifyColors.TextMuted, RoundedCornerShape(50)) else Modifier.background(Color.White))
                .quietClick(onAction).padding(horizontal = 18.dp, vertical = 9.dp),
        ) { Text(action, color = if (connected) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().quietClick(onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), color = Color.White, fontSize = 16.sp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SpotifyColors.TextSecondary)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = SpotifyColors.Elevated,
        title = { Text(title, color = Color.White) }, text = { Text(body, color = SpotifyColors.TextSecondary) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onConfirm) { Text(confirm, color = SpotifyColors.Green) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } },
    )
}

// ---------------------------------------------------------------------------------------------
// Welcome / log in, in Spotify's style, with Spotifeur's sign-in flows behind it.
// ---------------------------------------------------------------------------------------------

private enum class LoginStep { Welcome, Email, Password }

@Composable
fun SpotifireWelcomeScreen(model: SpotifeurModel, account: AccountUi) {
    var step by remember { mutableStateOf(LoginStep.Welcome) }
    var google by remember { mutableStateOf(false) }
    var googleFailed by remember { mutableStateOf(false) }
    if (google) {
        SpotifyGoogleSignIn(
            onSigned = { code, idToken -> google = false; googleFailed = false; model.connectSpotifyGoogle(code, idToken, SPOTIFY_GOOGLE_REDIRECT) },
            onFailed = { google = false; googleFailed = true },
            onClose = { google = false },
        )
        return
    }
    androidx.activity.compose.BackHandler(enabled = step != LoginStep.Welcome || account.codeLength != null) {
        if (account.codeLength != null || account.working) model.disconnectSpotify() else step = LoginStep.Welcome
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2A2A), Color.Black, Color.Black)))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (step == LoginStep.Welcome && account.codeLength == null) 160.dp else 64.dp))
            Box(Modifier.size(56.dp).clip(CircleShape).background(SpotifyColors.Green), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_source_spotify), null, tint = Color.Black, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(24.dp))
            when {
                account.codeLength != null -> CodeStep(model, account)
                step == LoginStep.Welcome -> {
                    Text("Millions of songs.\nAll your music, in one place.", color = Color.White, fontSize = 28.sp, fontFamily = SpotifyMixTitle,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 34.sp)
                    Spacer(Modifier.height(48.dp))
                    BigButton("Log in to Spotify with email", filled = true) { step = LoginStep.Email }
                    BigButton("Continue with Google", leading = R.drawable.ic_google, enabled = !account.working) { googleFailed = false; google = true }
                    BigButton("Log in with password") { step = LoginStep.Password }
                    Spacer(Modifier.height(8.dp))
                    Text("Continue without Spotify", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp).quietClick(model::continueWithoutAccount))
                    Text("Use YouTube Music and the music on your phone. You can connect Spotify later.", color = SpotifyColors.TextSecondary,
                        fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                else -> CredentialsStep(model, account, step == LoginStep.Password)
            }
            (account.error ?: "Google sign-in failed. Try again or use your email.".takeIf { googleFailed && !account.working })?.let {
                Text(it, color = SpotifyColors.Error, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun CredentialsStep(model: SpotifeurModel, account: AccountUi, password: Boolean) {
    val focus = LocalFocusManager.current
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Text(if (password) "Log in with your password" else "What's your email?", color = Color.White, fontSize = 24.sp, fontFamily = SpotifyMixTitle,
        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    if (!password) Text("Spotify sends you a code to sign in.", color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(24.dp))
    SpotifyField(username, { username = it }, if (password) "Email or username" else "Email address", KeyboardType.Email, enabled = !account.working)
    if (password) {
        Spacer(Modifier.height(12.dp))
        SpotifyField(secret, { secret = it }, "Password", KeyboardType.Password, secret = true, enabled = !account.working)
    }
    Spacer(Modifier.height(24.dp))
    BigButton(
        when { account.working -> "Connecting…"; password -> "Log in"; else -> "Send code" }, filled = true,
        enabled = !account.working && username.isNotBlank() && (!password || secret.isNotEmpty()),
    ) {
        focus.clearFocus()
        if (password) model.connectSpotify(username, secret) else model.connectSpotifyEmail(username)
        secret = ""
    }
    if (account.working) Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(12.dp).quietClick(model::disconnectSpotify))
}

@Composable
private fun CodeStep(model: SpotifeurModel, account: AccountUi) {
    val length = account.codeLength ?: 6
    var code by remember { mutableStateOf("") }
    Text("Enter the code", color = Color.White, fontSize = 24.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
    Text(account.codeEmail?.let { "We sent a $length-digit code to $it." } ?: "We sent you a $length-digit code.", color = SpotifyColors.TextSecondary,
        fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(24.dp))
    SpotifyField(code, { value -> code = value.filter { it in '0'..'9' }.take(length) }, "Code", KeyboardType.Number)
    Spacer(Modifier.height(24.dp))
    BigButton("Log in", filled = true, enabled = code.length == length) { model.submitCode(code); code = "" }
    Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp).quietClick(model::disconnectSpotify))
}

@Composable
private fun SpotifyField(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, secret: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = SpotifyColors.Highlight, unfocusedContainerColor = SpotifyColors.Highlight,
            focusedBorderColor = Color.White, unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = Color.White, unfocusedLabelColor = SpotifyColors.TextSecondary, cursorColor = SpotifyColors.Green,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BigButton(label: String, filled: Boolean = false, leading: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).height(50.dp).clip(RoundedCornerShape(50))
            .then(if (filled) Modifier.background(if (enabled) SpotifyColors.Green else SpotifyColors.Green.copy(alpha = 0.4f))
                else Modifier.border(1.dp, SpotifyColors.TextMuted, RoundedCornerShape(50)))
            .then(if (enabled) Modifier.quietClick(onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) { Icon(painterResource(leading), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
        Text(label, color = if (filled) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
