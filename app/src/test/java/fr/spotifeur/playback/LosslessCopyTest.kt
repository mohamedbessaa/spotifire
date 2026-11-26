package fr.spotifeur.playback

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lossless copy is a different file from the AAC one: it is kept under its own name, so neither is served as the other. */
class LosslessCopyTest {
    @Test
    fun losslessHasItsOwnCacheKey() {
        val id = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
        assertNotEquals(YouTubeUris.cacheKey(id, lossless = true), YouTubeUris.cacheKey(id, lossless = false))
        assertTrue(YouTubeUris.cacheKey(id, lossless = true).endsWith("-flac"))
        assertTrue("the AAC name is unchanged, so what is cached today stays usable", YouTubeUris.cacheKey(id) == YouTubeUris.cacheKey(id, lossless = false))
    }
}
