package fr.spotifeur.spotifire.friends

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.presentation.viewmodel.PlayerViewModel.FriendPick as Pick
import fr.spotifeur.data.Music
import fr.spotifeur.social.Directory
import fr.spotifeur.social.DirectoryEntry
import fr.spotifeur.social.Friend
import fr.spotifeur.social.Handles
import fr.spotifeur.social.Identity
import fr.spotifeur.social.Social
import fr.spotifeur.social.SocialMode
import fr.spotifeur.social.SocialSnapshot
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendsSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SpotifyColors.Elevated, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 16.dp)) { content() }
    }
}

@Composable
private fun SheetTitle(text: String) = Text(text, color = Color.White, fontSize = 20.sp, fontFamily = SpotifyMixTitle,
    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

/** Find people by exact name or handle, or scan their code. */
@Composable
internal fun AddFriendSheet(snap: SocialSnapshot, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DirectoryEntry>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<DirectoryEntry?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val parsed = Social.parseQr(text)
        if (parsed == null) scanError = "That's not a Spotifire friend code." else { scanned = DirectoryEntry(parsed.first, parsed.second, null); scanError = null }
    }
    LaunchedEffect(query) {
        if (Directory.key(query.substringBefore('#')).length < 2) { results = null; return@LaunchedEffect }
        delay(500)
        searching = true
        results = runCatching { Social.lookup(query) }.getOrDefault(emptyList())
        searching = false
    }
    FriendsSheet(onDismiss) {
        SheetTitle("Add friends")
        Box(Modifier.padding(horizontal = 16.dp)) {
            SpotifyField(query, { query = it }, "Name or name#CODE", leading = { Icon(Icons.Filled.Search, null, tint = SpotifyColors.TextSecondary, modifier = Modifier.size(18.dp)) })
        }
        Row(Modifier.fillMaxWidth().quietClick {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan a friend's Spotifire code")
                .setBeepEnabled(false).setOrientationLocked(false))
        }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.QrCodeScanner, null, tint = Color.White)
            Spacer(Modifier.width(14.dp))
            Text("Scan a friend's code", color = Color.White, fontSize = 15.sp)
        }
        scanError?.let { Text(it, color = SpotifyColors.Error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        scanned?.let { entry -> PersonResult(entry, snap) { Social.launch { Social.sendRequest(entry.pubKey, entry.name) } } }
        when {
            searching -> Text("Searching…", color = SpotifyColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            results?.isEmpty() == true -> Text("Nobody found with that exact name. Names must match exactly; a friend who hid their name can show you their code.",
                color = SpotifyColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            results != null -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(results.orEmpty(), key = { it.pubKey }) { entry ->
                    PersonResult(entry, snap) { Social.launch { Social.sendRequest(entry.pubKey, entry.name) } }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PersonResult(entry: DirectoryEntry, snap: SocialSnapshot, onAdd: () -> Unit) {
    val friend = snap.friends.any { it.pubKey == entry.pubKey }
    val pending = entry.pubKey in snap.pending
    val lookalike = Directory.looksLikeFriend(entry, snap.friends)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FriendAvatar(entry.pubKey, entry.name, 48.dp, photo = entry.thumbnail)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name.ifBlank { "Spotifire user" }, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Handles.handle(entry.name, entry.pubKey), color = SpotifyColors.TextSecondary, fontSize = 13.sp)
            if (lookalike) Text("Same name as a friend, but a different person", color = SpotifyColors.Error, fontSize = 12.sp)
        }
        when {
            friend -> Text("Friends", color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            pending -> Text("Requested", color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            entry.pubKey in snap.blocked -> Text("Blocked", color = SpotifyColors.TextSecondary, fontSize = 13.sp)
            else -> PillButton("Add", filled = true, onClick = onAdd)
        }
    }
}

/** The code a friend scans to add you, even with your name hidden. */
@Composable
internal fun MyCodeSheet(snap: SocialSnapshot, onDismiss: () -> Unit) {
    val payload = remember(snap.me, snap.profile.name) { Social.qrPayload() }
    val bitmap = remember(payload) { payload?.let(::qrBitmap) }
    FriendsSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) {
                bitmap?.let { Image(it.asImageBitmap(), "Your friend code", Modifier.size(220.dp), filterQuality = FilterQuality.None) }
            }
            Spacer(Modifier.height(20.dp))
            Text(snap.profile.name, color = Color.White, fontSize = 22.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
            snap.me?.let { Text(Handles.handle(snap.profile.name, it), color = SpotifyColors.TextSecondary, fontSize = 15.sp) }
            Spacer(Modifier.height(12.dp))
            Text("Friends scan this in Friends › Add friends. Your name and code are also how they find you.",
                color = SpotifyColors.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val size = matrix.width
    val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** Picks friends and sends them [item] with an optional note, inside the app. */
@Composable
fun SendToFriendsSheet(
    model: SpotifeurModel, item: Music, mode: fr.spotifeur.presentation.viewmodel.PlayerViewModel.FriendPick,
    onOpenFriends: () -> Unit, onDismiss: () -> Unit,
) {
    val snap by Social.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    var allowQueue by remember { mutableStateOf(true) }
    FriendsSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(item.imageUrl, Modifier.size(48.dp), RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(when (mode) { Pick.Send -> "Send to friends"; Pick.EditTogether -> "Edit together"; Pick.ListenTogether -> "Listen together" },
                    color = SpotifyColors.TextSecondary, fontSize = 13.sp)
                Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!SocialMode.enabled(context) || snap.friends.isEmpty()) {
            Text(if (!SocialMode.enabled(context)) "Set up Friends to send music to people you know, right here in Spotifire."
                else "You have no friends here yet. Add them by name or code.",
                color = SpotifyColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            PillButton("Go to Friends", filled = true, modifier = Modifier.padding(horizontal = 20.dp)) { onDismiss(); onOpenFriends() }
            return@FriendsSheet
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(snap.friends.sortedBy { it.name.lowercase() }, key = { it.pubKey }) { friend ->
                val selected = friend.pubKey in chosen
                Row(Modifier.fillMaxWidth().quietClick { chosen = if (selected) chosen - friend.pubKey else chosen + friend.pubKey }
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FriendAvatar(friend.pubKey, friend.name, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(friend.name, Modifier.weight(1f), color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(24.dp).clip(CircleShape).then(if (selected) Modifier.background(SpotifyColors.Green)
                        else Modifier.border(1.5.dp, SpotifyColors.TextSecondary, CircleShape)), contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        when (mode) {
            Pick.Send -> Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { SpotifyField(note, { note = it.take(140) }, "Add a note (optional)", names = false) }
            Pick.EditTogether -> Text("Friends you invite can add, remove and reorder songs. Each of them chooses to join.",
                color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            Pick.ListenTogether -> Column(Modifier.padding(horizontal = 20.dp)) {
                Text("You control the music; friends who join hear the same thing at the same moment. Each of them chooses to join.",
                    color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                SwitchRow("Friends can add songs", "They suggest songs to your queue", allowQueue) { allowQueue = it }
            }
        }
        val label = when (mode) { Pick.Send -> if (chosen.size > 1) "Send to ${chosen.size}" else "Send"; else -> if (chosen.size > 1) "Invite ${chosen.size}" else "Invite" }
        PillButton(label, filled = true, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            when (mode) {
                Pick.Send -> model.ui.sendToFriends(item, chosen.toList(), note.trim())
                Pick.EditTogether -> model.ui.editTogether(item, chosen.toList())
                Pick.ListenTogether -> model.ui.listenTogether(item, chosen.toList(), allowQueue)
            }
            onDismiss()
        }
    }
}

/** A friend: their name and code, and what you can do with them. */
@Composable
internal fun FriendProfileSheet(model: SpotifeurModel, friend: Friend, snap: SocialSnapshot, onDismiss: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    val fromThem = snap.inbox.count { it.from == friend.pubKey }
    val photo = snap.photos[friend.pubKey]
    val bio = snap.profiles[friend.pubKey]?.bio.orEmpty()
    val wash = fr.spotifeur.spotifire.components.rememberArtworkColor(photo)
    FriendsSheet(onDismiss) {
        // Spotify's profile header: the photo's colour washing down into the sheet.
        Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(wash.copy(alpha = 0.55f), Color.Transparent)))
            .padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FriendAvatar(friend.pubKey, friend.name, 132.dp, photo = photo)
            Spacer(Modifier.height(14.dp))
            Text(friend.name, color = Color.White, fontSize = 26.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
            Text(Handles.handle(friend.name, friend.pubKey), color = SpotifyColors.TextSecondary, fontSize = 14.sp)
            if (bio.isNotBlank()) Text(bio, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
            Text("Friends since " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(friend.since * 1000)) +
                if (fromThem > 0) " · sent you $fromThem" else "", color = SpotifyColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        snap.listening[friend.pubKey]?.let { now ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Highlight)
                .quietClick {
                    model.ui.playItem(Music(now.id, now.title, now.subtitle, fr.spotifeur.data.MusicKind.Track, imageUrl = now.image))
                    onDismiss()
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(now.image, Modifier.size(48.dp), RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Listening now", color = SpotifyColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(now.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.subtitle, color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White)
            }
        }
        val context = LocalContext.current
        val blends by fr.spotifeur.social.Blends.views.collectAsStateWithLifecycle()
        val playing by model.player.state.collectAsStateWithLifecycle()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val blend = blends.firstOrNull { it.friend == friend.pubKey }
            PillButton(when { blend == null -> "Create a Blend"; blend.waiting -> "Blend: waiting"; else -> "Open Blend" }, filled = blend == null,
                modifier = Modifier.weight(1f), enabled = blend?.waiting != true) {
                if (blend == null) Social.launch { fr.spotifeur.social.Blends.invite(context, friend.pubKey); model.ui.sendToast("Blend invitation sent") }
                else blend.localId?.let { id -> model.ui.entity(id)?.let { onDismiss(); model.ui.openShared(it) } }
            }
            val track = playing.track
            PillButton("Listen together", filled = false, modifier = Modifier.weight(1f), enabled = track != null) {
                track?.let { model.ui.listenTogether(it, listOf(friend.pubKey), allowQueue = true) }
                onDismiss()
            }
        }
        Spacer(Modifier.height(8.dp))
        when (confirm) {
            null -> {
                ActionRow("Remove friend") { confirm = "remove" }
                ActionRow("Block", destructive = true) { confirm = "block" }
            }
            else -> {
                Text(if (confirm == "block") "Block ${friend.name}? They can't send you anything or add you again until you unblock them."
                    else "Remove ${friend.name} from your friends?", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Cancel", filled = false, modifier = Modifier.weight(1f)) { confirm = null }
                    PillButton(if (confirm == "block") "Block" else "Remove", filled = true, modifier = Modifier.weight(1f)) {
                        val action = confirm
                        Social.launch { if (action == "block") Social.block(friend.pubKey) else Social.remove(friend.pubKey) }
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** Your name, who can find you, how notifications arrive, your recovery phrase and blocked people. */
@Composable
internal fun FriendsSettingsSheet(snap: SocialSnapshot, onDismiss: () -> Unit) {
    // Typed changes are kept when the sheet closes, not only on the keyboard's Done.
    val latest = androidx.compose.runtime.rememberUpdatedState(snap.profile)
    var pendingName by remember { mutableStateOf<String?>(null) }
    var pendingBio by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val n = pendingName?.trim()?.ifBlank { null }; val b = pendingBio?.trim()
            if (n != null && n != latest.value.name || b != null && b != latest.value.bio) {
                Social.launch { Social.updateProfile(latest.value.copy(name = n ?: latest.value.name, bio = b ?: latest.value.bio)) }
            }
        }
    }
    val context = LocalContext.current
    var name by remember { mutableStateOf(snap.profile.name) }
    var instant by remember { mutableStateOf(SocialMode.instant(context)) }
    var showWords by remember { mutableStateOf(false) }
    val words by produceState<String?>(null, showWords) { if (showWords) value = Identity.current(context).words }
    val confirmLock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) showWords = true
    }
    var bio by remember { mutableStateOf(snap.profile.bio) }
    var photoBusy by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        photoBusy = true; photoError = null
        Social.launch {
            runCatching {
                val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { fr.spotifeur.social.ProfileImages.prepare(context, uri) }
                Social.setPhoto(prepared)
            }.onFailure { photoError = it.message ?: "Couldn't set the photo." }
            photoBusy = false
        }
    }
    FriendsSheet(onDismiss) {
        SheetTitle("Your profile")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                FriendAvatar(snap.me.orEmpty(), name, 80.dp, photo = snap.profile.photo)
                if (photoBusy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                PillButton(if (snap.profile.photo == null) "Add photo" else "Change photo", filled = false, enabled = !photoBusy) {
                    pick.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (snap.profile.photo != null && !photoBusy) Text("Remove photo", color = SpotifyColors.TextSecondary, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp).quietClick { Social.launch { Social.removePhoto() } })
                photoError?.let { Text(it, color = SpotifyColors.Error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        Text(if (snap.profile.discoverable) "Friends see your photo. A small copy shows next to your name when people search for you."
            else "Only friends see your photo, encrypted.", color = SpotifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Your name", color = SpotifyColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            SpotifyField(name, { name = it.take(Directory.MAX_NAME); pendingName = name }, "Your name", imeAction = ImeAction.Done,
                onDone = { if (name.isNotBlank()) Social.launch { Social.updateProfile(snap.profile.copy(name = name.trim())) } })
            snap.me?.let { Text("Your code: ${Handles.handle(name, it)}", color = SpotifyColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            Text("About you", color = SpotifyColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            SpotifyField(bio, { bio = it.take(160); pendingBio = bio }, "A line friends see on your profile", imeAction = ImeAction.Done, names = false,
                onDone = { Social.launch { Social.updateProfile(snap.profile.copy(name = name.trim().ifBlank { snap.profile.name }, bio = bio.trim())) } })
        }
        Text("Friends settings", color = Color.White, fontSize = 18.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(horizontal = 20.dp)) {
            SwitchRow("Let people find me by name", "Off: only people with your code can add you", snap.profile.discoverable) {
                Social.launch { Social.updateProfile(snap.profile.copy(name = name.trim().ifBlank { snap.profile.name }, discoverable = it)) }
            }
        }
        var sharing by remember { mutableStateOf(SocialMode.shareListening(context)) }
        Box(Modifier.padding(horizontal = 20.dp)) {
            SwitchRow("Share what I'm listening to", "Friends see the song you're playing. Files on your phone are never shown.", sharing) {
                sharing = it; SocialMode.setShareListening(context, it)
            }
        }
        Box(Modifier.padding(horizontal = 20.dp)) {
            SwitchRow("Instant notifications", if (instant) "Requests and music arrive right away" else "Checked every 15 minutes, to save battery", instant) {
                instant = it; SocialMode.setInstant(context, it)
            }
        }
        ActionRow("Recovery phrase") {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = keyguard?.takeIf { it.isDeviceSecure }?.createConfirmDeviceCredentialIntent("Recovery phrase", "Confirm it's you to see your 12 words")
            if (intent != null) confirmLock.launch(intent) else showWords = true
        }
        if (showWords) Column(Modifier.padding(horizontal = 20.dp)) {
            Text(words ?: "This identity was restored from a password export and has no recovery words.",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyColors.Highlight).padding(14.dp))
            Text("These 12 words bring your friends back on a new phone. Anyone with them can act as you: never share them.",
                color = SpotifyColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (snap.blocked.isNotEmpty()) {
            Text("Blocked", color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            snap.blocked.forEach { key ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Handles.handle("", key), Modifier.weight(1f), color = Color.White, fontSize = 15.sp)
                    PillButton("Unblock", filled = false) { Social.launch { Social.unblock(key) } }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(label, color = if (destructive) SpotifyColors.Error else Color.White, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().quietClick(onClick).padding(horizontal = 20.dp, vertical = 16.dp))
}

@Composable
internal fun PillButton(label: String, filled: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(modifier.heightIn(min = 40.dp).clip(shape)
        .then(if (filled) Modifier.background(if (enabled) Color.White else SpotifyColors.Highlight) else Modifier.border(1.dp, SpotifyColors.TextMuted, shape))
        .then(if (enabled) Modifier.quietClick(onClick) else Modifier).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (filled) (if (enabled) Color.Black else SpotifyColors.TextMuted) else Color.White,
            fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SpotifyField(
    value: String, onChange: (String) -> Unit, hint: String, singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Search, onDone: () -> Unit = {}, leading: (@Composable () -> Unit)? = null,
    /** Names and handles are typed as they are: no autocorrect, capital letter first. Notes keep the keyboard's help. */
    names: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(SpotifyColors.Highlight).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading?.let { it(); Spacer(Modifier.width(8.dp)) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = SpotifyColors.TextSecondary, fontSize = 15.sp)
            BasicTextField(value, onChange, singleLine = singleLine, minLines = if (singleLine) 1 else 3,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(SpotifyColors.Green),
                keyboardOptions = if (names) KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false,
                    capitalization = if (singleLine) androidx.compose.ui.text.input.KeyboardCapitalization.Words else androidx.compose.ui.text.input.KeyboardCapitalization.None, keyboardType = androidx.compose.ui.text.input.KeyboardType.Text)
                    else KeyboardOptions(imeAction = imeAction, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences), keyboardActions = KeyboardActions(onAny = { onDone() }),
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().quietClick { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = SpotifyColors.TextSecondary, fontSize = 13.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyColors.Green,
            uncheckedThumbColor = SpotifyColors.TextSecondary, uncheckedTrackColor = SpotifyColors.Highlight, uncheckedBorderColor = Color.Transparent))
    }
}
