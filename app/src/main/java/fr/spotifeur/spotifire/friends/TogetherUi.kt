package fr.spotifeur.spotifire.friends

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.spotifeur.social.BlendInvite
import fr.spotifeur.social.Blends
import fr.spotifeur.social.Jam
import fr.spotifeur.social.JamInvite
import fr.spotifeur.social.PlaylistInvite
import fr.spotifeur.social.SharedPlaylists
import fr.spotifeur.social.SharedView
import fr.spotifeur.social.Social
import fr.spotifeur.social.SocialSnapshot
import fr.spotifeur.spotifire.components.Cover
import fr.spotifeur.spotifire.components.quietClick
import fr.spotifeur.spotifire.theme.SpotifyColors
import fr.spotifeur.spotifire.theme.SpotifyMixTitle
import kotlinx.coroutines.launch

/** An invitation with what it is, who sent it, and the two choices. Nothing happens until one is chosen. */
@Composable
private fun InvitationRow(icon: ImageVector, image: String?, title: String, subtitle: String, accept: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(SpotifyColors.Highlight), contentAlignment = Alignment.Center) {
            if (image != null) Cover(image, Modifier.size(56.dp), RoundedCornerShape(4.dp))
            else Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = SpotifyColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            PillButton(accept, filled = true, onClick = onAccept)
            Text("Decline", color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp).quietClick(onDecline).padding(4.dp))
        }
    }
}

@Composable
internal fun PlaylistInviteRow(invite: PlaylistInvite, snap: SocialSnapshot) {
    val context = LocalContext.current
    val others = invite.members.filter { it != snap.me && it != invite.from }.size
    InvitationRow(Icons.Outlined.QueueMusic, null, "${nameOf(snap, invite.from)} invited you to edit “${invite.title}”",
        "${invite.songs} songs" + if (others > 0) " · with $others more" else "", "Join",
        onAccept = { Social.launch { SharedPlaylists.join(context, invite.id) } }, onDecline = { Social.launch { SharedPlaylists.decline(context, invite.id) } })
}

@Composable
internal fun JamInviteRow(invite: JamInvite, snap: SocialSnapshot) {
    val context = LocalContext.current
    InvitationRow(Icons.Outlined.Headphones, invite.image, "${nameOf(snap, invite.from)} wants to listen together",
        "${invite.title} · ${invite.subtitle}. Your queue is kept and comes back when you leave.", "Join",
        onAccept = { Social.launch { Jam.join(context, invite.session) } }, onDecline = { Social.launch { Jam.decline(invite.session) } })
}

@Composable
internal fun BlendInviteRow(invite: BlendInvite, snap: SocialSnapshot) {
    val context = LocalContext.current
    InvitationRow(Icons.Outlined.Shuffle, null, "${nameOf(snap, invite.from)} wants to make a Blend",
        "50 songs from both your tastes, new every day. Your likes and most played songs are shared with them.", "Accept",
        onAccept = { Social.launch { Blends.accept(context, invite.id) } }, onDecline = { Social.launch { Blends.decline(context, invite.id) } })
}

private fun nameOf(snap: SocialSnapshot, key: String) = snap.friends.firstOrNull { it.pubKey == key }?.name ?: "A friend"

/** The Jam in the Friends tab: who is in, what plays, and how to leave. */
@Composable
internal fun JamCard(snap: SocialSnapshot) {
    val view by Jam.view.collectAsStateWithLifecycle()
    val jam = view ?: return
    Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E3A2A)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.GraphicEq, null, tint = SpotifyColors.Green)
            Spacer(Modifier.width(8.dp))
            Text(if (jam.hosting) "You're hosting a Jam" else "Listening with ${nameOf(snap, jam.host)}", color = Color.White,
                fontSize = 17.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold)
        }
        jam.track?.let { t ->
            Text("${t.optString("title")} · ${t.optString("subtitle")}", color = SpotifyColors.TextSecondary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        val inside = if (jam.hosting) jam.joined else jam.joined + jam.host
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            inside.take(6).forEachIndexed { i, key ->
                FriendAvatar(key, nameOf(snap, key), 28.dp, Modifier.offset(x = (-8 * i).dp).border(2.dp, Color(0xFF1E3A2A), CircleShape), photo = snap.photos[key])
            }
            Spacer(Modifier.width(4.dp))
            Text(when {
                jam.hosting && jam.joined.isEmpty() -> "Waiting for friends to join"
                jam.hosting -> "${jam.joined.size} joined"
                !jam.offsetKnown -> "Syncing with ${nameOf(snap, jam.host)}…"
                else -> "In sync"
            }, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        // Everyone steers the Jam; this says who did what last, so clashes are sorted out between friends.
        jam.lastAction?.let { (who, what) ->
            Text("${if (who == snap.me) "You" else nameOf(snap, who)} $what", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        if (jam.hosting) SwitchRow("Friends can add songs", "They suggest songs to your queue", jam.allowQueue) { Jam.setAllowQueue(it) }
        PillButton(if (jam.hosting) "End the Jam" else "Leave", filled = false, modifier = Modifier.padding(top = 8.dp)) { Social.launch { Jam.leave() } }
    }
}

/** A thin bar over the mini player while a Jam runs, from any screen. */
@Composable
fun JamBar(onOpen: () -> Unit) {
    val view by Jam.view.collectAsStateWithLifecycle()
    val jam = view ?: return
    val snap by Social.snapshot.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E3A2A))
        .quietClick(onOpen).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.GraphicEq, null, tint = SpotifyColors.Green, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (jam.hosting) (if (jam.joined.isEmpty()) "Jam · waiting for friends" else "Jam · ${jam.joined.size} listening with you")
            else "Listening with ${nameOf(snap, jam.host)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Open", color = SpotifyColors.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Members of a shared playlist: the creator adds or removes friends; anyone can leave (the songs stay). */
@Composable
internal fun SharedMembersSheet(view: SharedView, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val snap by Social.snapshot.collectAsStateWithLifecycle()
    val owner = view.owner == snap.me
    var confirmLeave by remember { mutableStateOf(false) }
    FriendsSheet(onDismiss) {
        Text("Edit together", color = Color.White, fontSize = 20.sp, fontFamily = SpotifyMixTitle, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        view.members.forEach { key ->
            val name = if (key == snap.me) "You" else nameOf(snap, key)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FriendAvatar(key, name, 40.dp, photo = if (key == snap.me) snap.profile.photo else snap.photos[key])
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = Color.White, fontSize = 15.sp)
                    val added = view.entries.count { it.addedBy == key }
                    Text((if (key == view.owner) "Created it · " else "") + "added $added", color = SpotifyColors.TextSecondary, fontSize = 13.sp)
                }
                if (owner && key != snap.me) Text("Remove", color = SpotifyColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.quietClick { Social.launch { SharedPlaylists.removeMember(context, view.id, key) } }.padding(8.dp))
            }
        }
        if (owner) {
            val candidates = snap.friends.filter { it.pubKey !in view.members }
            if (candidates.isNotEmpty()) {
                Text("Invite more friends", color = SpotifyColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                candidates.forEach { friend ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FriendAvatar(friend.pubKey, friend.name, 36.dp, photo = snap.photos[friend.pubKey])
                        Spacer(Modifier.width(12.dp))
                        Text(friend.name, Modifier.weight(1f), color = Color.White, fontSize = 15.sp)
                        PillButton("Invite", filled = false) { Social.launch { SharedPlaylists.addMember(context, view.id, friend.pubKey) } }
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (!confirmLeave) Text(if (owner) "Stop sharing" else "Leave", color = SpotifyColors.Error, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().quietClick { confirmLeave = true }.padding(horizontal = 20.dp, vertical = 16.dp))
        else Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(if (owner) "Friends keep the songs they have, but no more changes are shared." else "You keep the songs here as your own playlist.",
                color = Color.White, fontSize = 14.sp)
            Row(Modifier.padding(top = 12.dp)) {
                PillButton("Cancel", filled = false) { confirmLeave = false }
                Spacer(Modifier.width(12.dp))
                PillButton(if (owner) "Stop sharing" else "Leave", filled = true) { Social.launch { SharedPlaylists.leave(context, view.id) }; onDismiss() }
            }
        }
    }
}
