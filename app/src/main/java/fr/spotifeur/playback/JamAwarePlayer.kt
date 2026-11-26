package fr.spotifeur.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession

/**
 * The player the media session exposes. While the user is a guest in a Jam, a seek from the notification's seek bar
 * (the media notification controller, which also carries the headset keys) goes to the host like any other action,
 * instead of moving this phone alone until the Jam snaps it back.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class JamAwarePlayer(player: Player, private val session: () -> MediaSession?) : ForwardingPlayer(player) {
    private fun fromNotification(): Boolean {
        val s = session() ?: return false
        val controller = s.controllerForCurrentRequest ?: return false
        return s.isMediaNotificationController(controller)
    }

    override fun seekTo(positionMs: Long) {
        val remote = fr.spotifeur.social.Jam.remote
        if (remote != null && fromNotification() && remote.send(fr.spotifeur.social.JamCommand.Seek(positionMs))) return
        super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val remote = fr.spotifeur.social.Jam.remote
        if (remote != null && fromNotification() && mediaItemIndex == currentMediaItemIndex &&
            remote.send(fr.spotifeur.social.JamCommand.Seek(positionMs))) return
        super.seekTo(mediaItemIndex, positionMs)
    }
}
