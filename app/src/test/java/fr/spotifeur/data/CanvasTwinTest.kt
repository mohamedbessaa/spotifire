package fr.spotifeur.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasTwinTest {
    private fun track(id: String, title: String, artist: String, ms: Int) =
        Music(id, title, artist, MusicKind.Track, durationMs = ms)

    @Test fun exactTwinPicked() {
        val found = CanvasTwin.pick("Halo", "Beyonce", 261_000, listOf(
            track("spotify:track:other", "Halo", "Someone Else", 261_000),
            track("spotify:track:twin", "Halo", "Beyonce", 262_500),
        ))
        assertEquals("spotify:track:twin", found)
    }

    @Test fun durationOffRejected() {
        val found = CanvasTwin.pick("Halo", "Beyonce", 261_000, listOf(track("spotify:track:a", "Halo", "Beyonce", 265_000)))
        assertNull(found)
    }

    @Test fun liveVersionRejected() {
        val found = CanvasTwin.pick("Halo", "Beyonce", 261_000, listOf(
            track("spotify:track:live", "Halo - Live", "Beyonce", 261_000),
            track("spotify:track:karaoke", "Halo (Karaoke Version)", "Beyonce", 261_000),
        ))
        assertNull(found)
    }

    @Test fun remixKeptWhenSourceIsRemix() {
        val found = CanvasTwin.pick("Halo (Remix)", "Beyonce", 200_000, listOf(
            track("spotify:track:orig", "Halo", "Beyonce", 200_000),
            track("spotify:track:remix", "Halo (Remix)", "Beyonce", 200_500),
        ))
        assertEquals("spotify:track:remix", found)
    }

    @Test fun accentsAndFeatFolded() {
        val found = CanvasTwin.pick("Halo - Remastered 2020", "Beyoncé", 261_000, listOf(
            track("spotify:track:twin", "Halo (feat. X)", "Beyonce", 261_000),
        ))
        assertEquals("spotify:track:twin", found)
    }

    @Test fun liveInTheSongNameIsNotAVersion() {
        // "Live Forever" is a song name, not a live recording: only the version part of a title counts.
        val studio = track("spotify:track:studio", "Live Forever", "Oasis", 276_000)
        val concert = track("spotify:track:concert", "Live Forever - Live at Knebworth", "Oasis", 276_500)
        assertEquals("spotify:track:studio", CanvasTwin.pick("Live Forever", "Oasis", 276_000, listOf(concert, studio)))
        assertNull(CanvasTwin.pick("Live Forever", "Oasis", 276_000, listOf(concert)))
    }

    @Test fun moreVersionWordsCount() {
        for (title in listOf("Song (Sped-Up)", "Song (Remixed)", "Song - Unplugged", "Song (Slowed + Reverb)")) {
            assertNull(title, CanvasTwin.pick("Song", "Artist", 200_000, listOf(track("spotify:track:x", title, "Artist", 200_000))))
        }
    }
}
