package fr.spotifeur.playback

import fr.spotifeur.data.MusicKind
import fr.spotifeur.data.Music
import fr.spotifeur.data.Proto
import fr.spotifeur.data.decodeMetadataTrack
import fr.spotifeur.data.metadataIsrc
import fr.spotifeur.data.proto
import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteTest {
    private val spotify = "spotify:track:0000000000000000000001"

    @Test
    fun freeAccountsAndNoAccountReadSpotifyTracksFromTheirCopy() {
        assertTrue(AudioRoute.viaCopy(spotify, local = false, spotifySignedIn = false, limited = false))
        assertTrue(AudioRoute.viaCopy(spotify, local = false, spotifySignedIn = true, limited = true))
    }

    @Test
    fun premiumAccountsLocalFilesAndYouTubeTracksKeepTheirOwnPath() {
        assertFalse(AudioRoute.viaCopy(spotify, local = false, spotifySignedIn = true, limited = false))
        assertFalse(AudioRoute.viaCopy("ytm:track:abc", local = false, spotifySignedIn = true, limited = true))
        assertFalse(AudioRoute.viaCopy("content://media/1", local = true, spotifySignedIn = false, limited = true))
    }

    @Test
    fun aTrackWithoutCopyGoesToTheEngine() {
        val id = "spotify:track:0000000000000000000009"
        AudioRoute.noCopy(id)
        assertFalse(AudioRoute.viaCopy(id, local = false, spotifySignedIn = true, limited = true))
        // Without an account the engine is not available: the copy stays the only way.
        assertTrue(AudioRoute.viaCopy(id, local = false, spotifySignedIn = false, limited = true))
    }

    @Test
    fun aShuffleOnlyAccountNeverFallsBackToTheEngine() {
        val id = "spotify:track:0000000000000000000010"
        AudioRoute.noCopy(id)
        AudioRoute.attributesKnown(mapOf("type" to "free", "offline" to "false"))
        try {
            // The engine refuses every key on such an account: the track stays on its copy.
            assertTrue(AudioRoute.viaCopy(id, local = false, spotifySignedIn = true, limited = true))
        } finally {
            AudioRoute.attributesKnown(mapOf("type" to "free", "offline" to "true"))
        }
        assertFalse(AudioRoute.viaCopy(id, local = false, spotifySignedIn = true, limited = true))
    }

    @Test
    fun isrcIsReadFromMetadataExternalIds() {
        val bytes = proto {
            writeByteArray(1, ByteArray(16).also { it[15] = 1 })
            writeString(2, "Get Lucky")
            writeByteArray(10, proto { writeString(1, "upc"); writeString(2, "886443927087") })
            writeByteArray(10, proto { writeString(1, "isrc"); writeString(2, "usqx91300108") })
        }
        assertEquals("USQX91300108", metadataIsrc(Proto(bytes)))
        assertEquals("USQX91300108", decodeMetadataTrack(Proto(bytes))!!.isrc)
    }

    @Test
    fun malformedOrMissingIsrcIsIgnored() {
        val bytes = proto {
            writeByteArray(1, ByteArray(16)); writeString(2, "x")
            writeByteArray(10, proto { writeString(1, "isrc"); writeString(2, "short") })
        }
        assertNull(metadataIsrc(Proto(bytes)))
    }

    @Test
    fun isrcSearchKeepsTheRecordingAndRejectsCoversOfAnotherLength() {
        val query = MatchQuery(spotify, "Get Lucky", "Daft Punk, Pharrell Williams", 369_000, "Random Access Memories", isrc = "USQX91300108")
        val original = track("Get Lucky", "Daft Punk", 369_500)
        val cover = track("Get Lucky", "Karaoke Idols", 231_000)
        assertEquals(original, TrackMatcher.isrcMatch(query, listOf(cover, original))?.first)
        assertNull(TrackMatcher.isrcMatch(query, listOf(cover)))
    }

    @Test
    fun isrcSearchRejectsAnUnrelatedTitleOfTheSameLength() {
        val query = MatchQuery(spotify, "Get Lucky", "Daft Punk", 369_000, isrc = "USQX91300108")
        assertNull(TrackMatcher.isrcMatch(query, listOf(track("Instant Crush", "Daft Punk", 369_000))))
    }

    private fun track(title: String, artist: String, durationMs: Int) =
        Music("ytm:track:$title$artist", title, artist, MusicKind.Track, durationMs = durationMs)
}
