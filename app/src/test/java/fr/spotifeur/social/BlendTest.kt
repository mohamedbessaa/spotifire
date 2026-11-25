package fr.spotifeur.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendTest {
    private val sara = "b".repeat(64)
    private val me = "a".repeat(64)
    private fun t(n: Int, plays: Int, isrc: String? = null) = JSONObject().put("id", "spotify:track:${"%022d".format(n)}")
        .put("title", "Song $n").put("plays", plays).apply { isrc?.let { put("isrc", it) } }
    private val mine = (1..199).map { t(it, 300 - it) } + t(900, 50, "USRC10000001") // 200, the cap
    private val hers = (1000..1197).map { t(it, 1300 - it) } + t(5, 10) + t(901, 40, "USRC10000001")

    @Test
    fun bothPhonesBuildTheSameFiftySongs() {
        val onMine = BlendBuilder.build(me to mine, sara to hers, "2026-11-25")
        val onHers = BlendBuilder.build(sara to hers, me to mine, "2026-11-25")
        assertEquals(50, onMine.size)
        assertEquals(onMine.map { it.getString("id") }, onHers.map { it.getString("id") })
        assertEquals("no song twice", 50, onMine.map { it.getString("id") }.toSet().size)
    }

    @Test
    fun songsBothLoveComeFirstEvenAsDifferentCopies() {
        val blend = BlendBuilder.build(me to mine, sara to hers, "2026-11-25")
        val firstTwo = blend.take(2).map { it.getString("title") }.toSet()
        assertTrue(firstTwo.toString(), "Song 5" in firstTwo)
        // The same recording (ISRC) liked from two sources counts as shared, once.
        assertTrue(firstTwo.toString(), "Song 900" in firstTwo || "Song 901" in firstTwo)
        assertEquals(1, blend.count { it.optString("isrc") == "USRC10000001" })
    }

    @Test
    fun itIsFairAndChangesFromDayToDay() {
        val blend = BlendBuilder.build(me to mine, sara to hers, "2026-11-25")
        val fromMe = blend.count { it.getString("title").removePrefix("Song ").toInt() in 1..200 }
        val fromHer = blend.count { it.getString("title").removePrefix("Song ").toInt() in 1000..1199 }
        assertTrue("$fromMe vs $fromHer", kotlin.math.abs(fromMe - fromHer) <= 1)
        assertNotEquals(blend.map { it.getString("id") }, BlendBuilder.build(me to mine, sara to hers, "2026-11-26").map { it.getString("id") })
    }

    @Test
    fun aSmallTasteStillGivesWhatThereIs() {
        val blend = BlendBuilder.build(me to listOf(t(1, 3)), sara to listOf(t(2, 1)), "2026-11-25")
        assertEquals(setOf("Song 1", "Song 2"), blend.map { it.getString("title") }.toSet())
    }
}
