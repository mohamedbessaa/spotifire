package fr.spotifeur.presentation.screens

import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.youtube.YTM_ALBUM_PREFIX
import fr.spotifeur.presentation.components.TrackSwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistorySwipeTest {
    private fun music(id: String, kind: MusicKind) = Music(id, "Title", "", kind)
    private val track = music("spotify:track:4uLU6hMCjMI75M1A2tKUQC", MusicKind.Track)
    private val album = music("spotify:album:4LH4d3cOWNNsVw41Gqt2kv", MusicKind.Album)
    private val artist = music("spotify:artist:0k17h0D3J5VfsdmQ1iZtE9", MusicKind.Artist)
    private val playlist = music("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", MusicKind.Playlist)
    private val youTubeAlbum = music("${YTM_ALBUM_PREFIX}MPREb_abc", MusicKind.Album)

    @Test fun queueAndPlayNextApplyToEveryKind() {
        for (action in listOf(TrackSwipeAction.ADD_TO_QUEUE, TrackSwipeAction.PLAY_NEXT)) {
            for (item in listOf(track, album, artist, playlist, youTubeAlbum)) assertEquals(action, historySwipeAction(action, item))
        }
    }

    @Test fun likeOnlyWhereTheLibraryCanSaveIt() {
        assertEquals(TrackSwipeAction.LIKE, historySwipeAction(TrackSwipeAction.LIKE, track))
        assertEquals(TrackSwipeAction.LIKE, historySwipeAction(TrackSwipeAction.LIKE, album))
        assertEquals(TrackSwipeAction.LIKE, historySwipeAction(TrackSwipeAction.LIKE, artist))
        assertNull(historySwipeAction(TrackSwipeAction.LIKE, playlist))
        assertNull(historySwipeAction(TrackSwipeAction.LIKE, youTubeAlbum))
    }

    @Test fun noGestureWithoutAnAction() {
        for (setting in listOf(null, TrackSwipeAction.NONE, TrackSwipeAction.REMOVE)) {
            assertNull(historySwipeAction(setting, track))
            assertNull(historySwipeAction(setting, album))
        }
    }
}
