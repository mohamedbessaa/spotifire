package fr.spotifeur.playback

import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartShuffleTest {
    private fun t(id: String) = Music(id, id, "", MusicKind.Track)

    @Test
    fun oneRecommendationAfterEveryThreeTracks() {
        val context = (1..7).map { t("c$it") }
        val picks = listOf(t("r1"), t("r2"), t("r3"))
        assertEquals(listOf("c1", "c2", "c3", "r1", "c4", "c5", "c6", "r2", "c7"), interleaveSmart(context, picks).map { it.id })
    }

    @Test
    fun noPicksLeavesTheQueueAsItIs() {
        val context = (1..4).map { t("c$it") }
        assertEquals(context, interleaveSmart(context, emptyList()))
    }
}
