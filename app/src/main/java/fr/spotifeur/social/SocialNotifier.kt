package fr.spotifeur.social

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Friends notifications: requests, accepted requests and shares, each opening the Friends tab. */
object SocialNotifier {
    const val REQUESTS = "friends_requests"
    const val SHARES = "friends_shares"
    const val CONNECTION = "friends_connection"
    const val PLAYLISTS = "friends_playlists"
    const val JAM = "friends_jam"
    const val EXTRA_OPEN_FRIENDS = "open_friends"

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(REQUESTS, "Friend requests", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SHARES, "Music from friends", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CONNECTION, "Friends connection", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
            NotificationChannel(PLAYLISTS, "Shared playlists", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(JAM, "Listen together", NotificationManager.IMPORTANCE_HIGH),
        ))
    }

    fun request(context: Context, name: String) =
        post(context, REQUESTS, 1001, "${name.ifBlank { "Someone" }} wants to be friends", "Accept or decline in Friends")

    fun accepted(context: Context, name: String) =
        post(context, REQUESTS, 1002, "${name.ifBlank { "Your friend" }} accepted your request", "You can send each other music now")

    fun share(context: Context, id: String, from: String, item: JSONObject?, note: String) {
        val title = item?.optString("title").orEmpty()
        val count = item?.optJSONArray("tracks")?.length() ?: 0
        val what = if (count > 1) "$title · $count songs" else title
        post(context, SHARES, id.hashCode(), "${from.ifBlank { "A friend" }} sent you $what", note.ifBlank { "Tap to listen" })
    }

    fun playlistInvite(context: Context, from: String, title: String) =
        post(context, PLAYLISTS, ("invite" + title).hashCode(), "$from invited you to edit “$title” together", "Join or decline in Friends")

    fun playlistEdits(context: Context, from: String, count: Int, title: String) =
        post(context, PLAYLISTS, ("edits" + title).hashCode(), "$from added ${if (count == 1) "a song" else "$count songs"} to “$title”", "Tap to open Friends")

    fun jamInvite(context: Context, from: String, what: String) =
        post(context, JAM, 3001, "$from wants to listen together", "$what · join or decline in Friends")

    fun blendInvite(context: Context, from: String) =
        post(context, PLAYLISTS, 3002, "$from wants to make a Blend with you", "Accept or decline in Friends")

    private fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        channels(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(EXTRA_OPEN_FRIENDS, true); flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = open?.let { PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(fr.spotifeur.R.drawable.ic_stat_spotifire)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(pending).build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
