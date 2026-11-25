package fr.spotifeur.social

import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedItemsTest {
    private val song = Music("spotify:track:4cOdK2wGLETKBW3PvgPWqT", "Never Gonna Give You Up", "Rick Astley", MusicKind.Track,
        imageUrl = "https://i.scdn.co/image/x", durationMs = 213_000, album = "Whenever You Need Somebody", isrc = "GBARL9300135")

    @Test
    fun aSongKeepsWhatPlaysItAnywhere() {
        val back = SharedItems.decode(SharedItems.encode(song, emptyList()))!!
        assertEquals(song.id, back.item.id)
        assertEquals("GBARL9300135", back.item.isrc)
        assertEquals(MusicKind.Track, back.item.kind)
        assertTrue(back.tracks.isEmpty())
    }

    @Test
    fun albumsAndArtistsTravelAsTheirIdAPlaylistAlsoCarriesItsSongs() {
        val album = Music("spotify:album:4uLU6hMCjMI75M1A2tKUQC", "Whenever", "Rick Astley", MusicKind.Album)
        val json = SharedItems.encode(album, listOf(song))
        assertNull("the receiver opens it from Spotify", json.optJSONArray("tracks"))
        assertEquals(album.id, SharedItems.decode(json)!!.item.id)
        // A private Spotify playlist cannot be opened by a friend: its songs come along.
        val playlist = Music("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "Mine", "Me", MusicKind.Playlist)
        val back = SharedItems.decode(SharedItems.encode(playlist, listOf(song)))!!
        assertEquals(playlist.id, back.item.id)
        assertEquals(listOf(song.id), back.tracks.map { it.id })
    }

    @Test
    fun aPlaylistThatOnlyLivesHereCarriesItsSongsButNoLocalFiles() {
        val mine = Music("local:playlist:12", "Road trip", "3 songs", MusicKind.Playlist)
        val file = Music("local:track:9", "Voice memo", "Me", MusicKind.Track, mediaUri = "content://media/9")
        val ytm = Music("ytm:track:dQw4w9WgXcQ", "Song", "Artist", MusicKind.Track)
        val back = SharedItems.decode(SharedItems.encode(mine, listOf(song, file, ytm)))!!
        assertEquals(listOf(song.id, ytm.id), back.tracks.map { it.id })
        assertTrue("a received playlist gets a new id here", back.item.id.startsWith("friend:"))
        assertEquals("Road trip", back.item.title)
    }

    @Test
    fun junkIsRefused() {
        assertNull(SharedItems.decode(org.json.JSONObject().put("kind", "song")))
        assertNull(SharedItems.decode(org.json.JSONObject().put("kind", "song").put("id", "content://x").put("title", "x")))
    }
}
