package fr.spotifeur.spotifire.friends

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.social.DirectoryEntry
import fr.spotifeur.social.Friend
import fr.spotifeur.social.FriendRequest
import fr.spotifeur.social.Handles
import fr.spotifeur.social.InboxItem
import fr.spotifeur.social.InboxState
import fr.spotifeur.social.OutboxItem
import fr.spotifeur.social.SharedItems
import fr.spotifeur.social.Social
import fr.spotifeur.social.SocialMode
import fr.spotifeur.social.SocialSnapshot
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.SectionTitle
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import kotlinx.coroutines.launch

/** The Friends tab: requests, music friends sent, friends, and what you sent. Everything stays in the app. */
@Composable
fun SpotifireFriendsScreen(model: SpotifeurModel, navController: NavHostController, bottomPadding: Int) {
    val context = LocalContext.current
    val snap by Social.snapshot.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(SocialMode.enabled(context)) }
    DisposableEffect(enabled) {
        if (enabled) Social.screenShown()
        onDispose { if (enabled) Social.screenHidden() }
    }
    if (!enabled || snap.me != null && snap.profile.name.isBlank()) {
        FriendsSetup(onDone = { enabled = true })
        return
    }
    FriendsHome(model, snap, bottomPadding)
}

@Composable
private fun FriendsHome(model: SpotifeurModel, snap: SocialSnapshot, bottomPadding: Int) {
    val ui = model.ui
    var adding by remember { mutableStateOf(false) }
    var showingCode by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var profileOf by remember { mutableStateOf<Friend?>(null) }
    // A friend code opened from the camera app or a link: shown in Add friends, ready to add.
    val incoming by Social.incomingCode.collectAsStateWithLifecycle()
    incoming?.let { (key, name) -> AddFriendSheet(snap, DirectoryEntry(key, name, null)) { Social.incomingCode.value = null } }
    if (adding) AddFriendSheet(snap) { adding = false }
    if (showingCode) MyCodeSheet(snap) { showingCode = false }
    if (settings) FriendsSettingsSheet(snap) { settings = false }
    profileOf?.let { friend -> FriendProfileSheet(model, friend, snap) { profileOf = null } }

    Column(Modifier.fillMaxSize().background(SpotifyColors.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FriendAvatar(snap.me.orEmpty(), snap.profile.name, 32.dp, Modifier.quietClick { settings = true }, photo = snap.profile.photo)
            Spacer(Modifier.width(12.dp))
            Text("Friends", Modifier.weight(1f), color = Color.White, fontSize = 24.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showingCode = true }) { Icon(Icons.Outlined.QrCode2, "My code", tint = Color.White) }
            IconButton(onClick = { adding = true }) { Icon(Icons.Outlined.PersonAdd, "Add friends", tint = Color.White) }
            IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Friends settings", tint = Color.White) }
        }
        if (!snap.connected) Text("Connecting…", color = SpotifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 60.dp))

        val playlistInvites by fr.spotifeur.social.SharedPlaylists.pendingInvites.collectAsStateWithLifecycle()
        val jamInvites by fr.spotifeur.social.Jam.pendingInvites.collectAsStateWithLifecycle()
        val blendInvites by fr.spotifeur.social.Blends.pendingInvites.collectAsStateWithLifecycle()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding.dp)) {
            item { JamCard(snap) }
            item { KeepConnectedCard() }
            if (jamInvites.isNotEmpty() || playlistInvites.isNotEmpty() || blendInvites.isNotEmpty()) {
                item { SectionTitle("Invitations") }
                items(jamInvites, key = { "j" + it.session }) { JamInviteRow(it, snap) }
                items(playlistInvites, key = { "s" + it.id }) { PlaylistInviteRow(it, snap) }
                items(blendInvites, key = { "b" + it.id }) { BlendInviteRow(it, snap) }
            }
            if (snap.requests.isNotEmpty()) {
                item { SectionTitle("Friend requests") }
                items(snap.requests, key = { "r" + it.pubKey }) { request -> RequestRow(request, snap) }
            }
            val inbox = snap.inbox.filter { it.state != InboxState.DECLINED }
            if (inbox.isNotEmpty()) {
                item { SectionTitle("From friends") }
                items(inbox, key = { "i" + it.id }) { entry ->
                    InboxRow(entry, nameOf(snap, entry.from), snap.photos[entry.from],
                        onPlay = { SharedItems.decode(entry.item)?.let(ui::playShared); Social.launch { Social.mark(entry.id, InboxState.SEEN) } },
                        onSave = { SharedItems.decode(entry.item)?.let(ui::saveShared); Social.launch { Social.mark(entry.id, InboxState.SAVED) } },
                        onDecline = { Social.launch { Social.mark(entry.id, InboxState.DECLINED) } })
                }
            }
            if (snap.friends.isEmpty()) item { EmptyFriends(onAdd = { adding = true }, onCode = { showingCode = true }) }
            else {
                item { SectionTitle("Your friends") }
                // Friends playing something come first, as in Spotify's Friend Activity.
                items(snap.friends.sortedWith(compareBy({ it.pubKey !in snap.listening }, { it.name.lowercase() })), key = { "f" + it.pubKey }) { friend ->
                    FriendRow(friend, snap.photos[friend.pubKey], snap.profiles[friend.pubKey]?.bio.orEmpty(), snap.listening[friend.pubKey],
                        onOpen = { profileOf = friend })
                }
            }
            if (snap.pending.isNotEmpty()) {
                item { SectionTitle("Waiting for an answer") }
                items(snap.pending.entries.toList(), key = { "p" + it.key }) { (key, name) ->
                    PersonRow(key, name, subtitle = "Request sent")
                }
            }
            if (snap.outbox.isNotEmpty()) {
                item { SectionTitle("You sent") }
                items(snap.outbox.take(30), key = { "o" + it.id }) { sent -> SentRow(sent, snap) }
            }
        }
    }
}

private fun nameOf(snap: SocialSnapshot, key: String) = snap.friends.firstOrNull { it.pubKey == key }?.name ?: "A friend"

@Composable
private fun RequestRow(request: FriendRequest, snap: SocialSnapshot) {
    val lookalike = snap.friends.any { it.pubKey != request.pubKey && it.name.equals(request.name, ignoreCase = true) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FriendAvatar(request.pubKey, request.name, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(request.name.ifBlank { "Someone" }, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Handles.handle(request.name, request.pubKey), color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
            if (lookalike) Text("Not the ${request.name} you already know: a different code", color = SpotifyColors.Error, fontSize = 12.sp)
            if (request.note.isNotBlank()) Text("“${request.note}”", color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontStyle = FontStyle.Italic)
        }
        PillButton("Accept", filled = true) { Social.launch { Social.accept(request.pubKey) } }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { Social.launch { Social.decline(request.pubKey) } }) { Icon(Icons.Filled.Close, "Decline", tint = SpotifyColors.TextSecondary) }
    }
}

@Composable
private fun InboxRow(entry: InboxItem, from: String, fromPhoto: String?, onPlay: () -> Unit, onSave: () -> Unit, onDecline: () -> Unit) {
    val shared = remember(entry.id) { SharedItems.decode(entry.item) }
    val item = shared?.item
    val kind = when (item?.kind) {
        fr.spotifeur.data.MusicKind.Track -> "Song"; fr.spotifeur.data.MusicKind.Album -> "Album"
        fr.spotifeur.data.MusicKind.Artist -> "Artist"; else -> shared?.tracks?.size?.let { "Playlist · $it songs" } ?: "Playlist"
    }
    Row(Modifier.fillMaxWidth().quietClick(onPlay).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Cover(item?.imageUrl, Modifier.size(56.dp),
                if (item?.kind == fr.spotifeur.data.MusicKind.Artist) CircleShape else RoundedCornerShape(4.dp))
            if (entry.state == InboxState.NEW) Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(10.dp).clip(CircleShape)
                .background(SpotifyColors.Green).border(1.5.dp, Color.Black, CircleShape))
            // Who sent it, on the cover's corner.
            FriendAvatar(entry.from, from, 22.dp, Modifier.align(Alignment.BottomEnd).padding(1.dp).border(1.5.dp, Color.Black, CircleShape), photo = fromPhoto)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item?.title ?: "Something new", color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (entry.state == InboxState.NEW) FontWeight.Bold else FontWeight.Normal)
            Text("$from · $kind · ${ago(entry.at)}", color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.note.isNotBlank()) Text("“${entry.note}”", color = SpotifyColors.TextSecondary, fontSize = 13.sp,
                fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (entry.state == InboxState.SAVED) Icon(Icons.Filled.Check, "Saved", tint = SpotifyColors.Green, modifier = Modifier.padding(12.dp).size(22.dp))
        else IconButton(onClick = onSave) { Icon(Icons.Outlined.AddCircleOutline, "Save", tint = SpotifyColors.TextSecondary) }
        Box(Modifier.size(40.dp).clip(CircleShape).background(SpotifyColors.Green).quietClick(onPlay), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(24.dp))
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = SpotifyColors.TextSecondary) }
            DropdownMenu(menu, { menu = false }, containerColor = SpotifyColors.Elevated) {
                DropdownMenuItem(text = { Text("Not for me") }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = { menu = false; onDecline() })
            }
        }
    }
}

@Composable
private fun FriendRow(friend: Friend, photo: String?, bio: String, listening: fr.spotifeur.social.Listening?, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().quietClick(onOpen).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FriendAvatar(friend.pubKey, friend.name, 48.dp, photo = photo)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(friend.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (listening != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, null, tint = SpotifyColors.Green, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("${listening.title} · ${listening.subtitle}", color = SpotifyColors.Green, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(bio.ifBlank { Handles.handle(friend.name, friend.pubKey) }, color = SpotifyColors.TextSecondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PersonRow(key: String, name: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FriendAvatar(key, name, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name.ifBlank { Handles.handle("", key) }, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = SpotifyColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SentRow(sent: OutboxItem, snap: SocialSnapshot) {
    val item = remember(sent.id) { SharedItems.decode(sent.item)?.item }
    val to = sent.to.joinToString(", ") { nameOf(snap, it) }
    val best = sent.states.values.maxByOrNull { it.ordinal } ?: InboxState.NEW
    val status = when (best) { InboxState.NEW -> "Sent"; InboxState.SEEN -> "Played"; InboxState.SAVED -> "Saved"; InboxState.DECLINED -> "Passed" }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(item?.imageUrl, Modifier.size(48.dp), RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item?.title.orEmpty(), color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("To $to · ${ago(sent.at)}", color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(status, color = if (best == InboxState.SAVED || best == InboxState.SEEN) SpotifyColors.Green else SpotifyColors.TextSecondary,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyFriends(onAdd: () -> Unit, onCode: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Music is better with friends", color = Color.White, fontSize = 22.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Add friends by their name or code. Send them songs and playlists, build playlists together and listen at the same time.",
            color = SpotifyColors.TextSecondary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PillButton("Add friends", filled = true, onClick = onAdd)
        Spacer(Modifier.height(12.dp))
        PillButton("Show my code", filled = false, onClick = onCode)
    }
}

/** First visit: pick the name friends will see. Nothing is sent anywhere before this. */
@Composable
private fun FriendsSetup(onDone: () -> Unit) {
    val context = LocalContext.current
    val snap by Social.snapshot.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(snap.profile.name) }
    var discoverable by remember { mutableStateOf(true) }
    var restoring by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun start() {
        Social.launch {
            Social.updateProfile(fr.spotifeur.social.MyProfile(name.trim(), "", discoverable))
            SocialMode.setEnabled(context, true)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            onDone()
        }
    }
    Column(Modifier.fillMaxSize().background(SpotifyColors.Background).statusBarsPadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center) {
        Text("Friends", color = Color.White, fontSize = 32.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Send music, share playlists and listen together, inside Spotifire. Messages are end-to-end encrypted.",
            color = SpotifyColors.TextSecondary, fontSize = 15.sp)
        Spacer(Modifier.height(32.dp))
        if (!restoring) {
            Text("Your name", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            SpotifyField(name, { name = it.take(fr.spotifeur.social.Directory.MAX_NAME) }, "The name friends will see")
            Spacer(Modifier.height(16.dp))
            SwitchRow("Let people find me by name", "Off: friends add you with your code only", discoverable) { discoverable = it }
            Spacer(Modifier.height(32.dp))
            PillButton("Get started", filled = true, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { start() }
            Spacer(Modifier.height(16.dp))
            Text("I use Friends on another phone", color = SpotifyColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).quietClick { restoring = true }.padding(8.dp))
        } else {
            Text("Recovery phrase", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            SpotifyField(words, { words = it; error = null }, "The 12 words from your other phone", singleLine = false)
            error?.let { Text(it, color = SpotifyColors.Error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))
            Text("Your name", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            SpotifyField(name, { name = it.take(fr.spotifeur.social.Directory.MAX_NAME) }, "The name friends will see")
            Spacer(Modifier.height(32.dp))
            PillButton("Restore", filled = true, enabled = name.isNotBlank() && words.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                val keys = fr.spotifeur.social.SocialKeys.fromWords(words)
                if (keys == null) { error = "These are not 12 valid recovery words."; return@PillButton }
                Social.launch { Social.restore(keys); start() }
            }
            Spacer(Modifier.height(16.dp))
            Text("Back", color = SpotifyColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).quietClick { restoring = false }.padding(8.dp))
        }
    }
}

/** A friend's picture: their photo when there is one, else their initial on a colour of their own. */
@Composable
fun FriendAvatar(key: String, name: String, size: Dp, modifier: Modifier = Modifier, photo: String? = null) {
    val palette = remember { listOf(0xFFE8622C, 0xFF1ED760, 0xFF509BF5, 0xFFAF2896, 0xFFF59B23, 0xFF8D67AB, 0xFF27856A, 0xFFE91429) }
    val color = Color(palette[(key.hashCode() and 0x7fffffff) % palette.size])
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        // Without font padding and with a trimmed line height, the letter sits in the centre even at 15 dp.
        Text(initial, color = Color.Black, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeight = (size.value * 0.46f).sp,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)))
        if (photo != null) Cover(photo, Modifier.fillMaxSize(), CircleShape)
    }
}

internal fun ago(seconds: Long): String {
    val diff = (System.currentTimeMillis() / 1000 - seconds).coerceAtLeast(0)
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60} min"
        diff < 86_400 -> "${diff / 3600} h"
        diff < 7 * 86_400 -> "${diff / 86_400} d"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(seconds * 1000))
    }
}

/**
 * Asks once to let Friends run without battery limits, so Android does not stop the connection and invitations arrive
 * right away; hidden once allowed or dismissed (it stays available in Friends settings).
 */
@Composable
private fun KeepConnectedCard() {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(SocialMode.instant(context) && !SocialMode.unrestricted(context) && !SocialMode.askedUnrestricted(context)) }
    if (!shown) return
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(10.dp)).background(SpotifyColors.Highlight).padding(16.dp)) {
        Text("Get invites right away", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Android may pause Friends in the background, and requests or Jam invitations then wait until you open the app. " +
            "Allow Spotifire to run in the background to keep it connected.", color = SpotifyColors.TextSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Allow", filled = true) {
                SocialMode.setAskedUnrestricted(context); shown = false
                runCatching { context.startActivity(SocialMode.unrestrictIntent(context)) }
            }
            Spacer(Modifier.width(12.dp))
            Text("Not now", color = SpotifyColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.quietClick { SocialMode.setAskedUnrestricted(context); shown = false }.padding(8.dp))
        }
    }
}
