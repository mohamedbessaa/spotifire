package fr.spotifeur.social

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionFilterTest {
    @Test
    fun alternatingJitterCancelsOut() {
        val f = PositionFilter()
        val jitter = listOf(50, 64, -160, 124, -202, 73, 84, -203, 136, -161, 123, 74)
        jitter.forEachIndexed { i, j -> f.add(readAt = 1000L + i * 500, position = 20_000L + i * 500 + j, rate = 1f, playing = true, track = "t") }
        // Truth at 7000: 20000 + 6000.
        assertEquals(26_000.0, f.estimate(7_000)!!.toDouble(), 40.0)
    }

    @Test
    fun aSeekATrackChangeOrAPauseStartsOver() {
        val f = PositionFilter()
        repeat(6) { f.add(1000L + it * 500, 10_000L + it * 500, 1f, true, "t") }
        f.add(4_000, 60_000, 1f, true, "t") // seek
        assertEquals(60_500.0, f.estimate(4_500)!!.toDouble(), 1.0)
        f.add(4_500, 0, 1f, true, "u") // next track
        assertEquals(100.0, f.estimate(4_600)!!.toDouble(), 1.0)
        f.add(5_000, 500, 1f, false, "u") // paused: the position stays
        assertEquals(500.0, f.estimate(9_000)!!.toDouble(), 1.0)
    }

    @Test
    fun aSpeedNudgeIsFollowed() {
        val f = PositionFilter()
        repeat(4) { f.add(1000L + it * 500, 10_000L + it * 500, 1f, true, "t") }
        // At 3% faster the position runs ahead of the clock; the filter restarts with the new rate.
        repeat(4) { f.add(3000L + it * 500, 12_000L + (it * 500 * 1.03).toLong(), 1.03f, true, "t") }
        assertEquals(12_000 + 2000 * 1.03, f.estimate(5_000)!!.toDouble(), 5.0)
    }

    @Test
    fun aJamTrimDoesNotStartOver() {
        // The trim moves the rate by a hair at every tick; that must not throw away the readings.
        val f = PositionFilter()
        val rates = listOf(1f, 0.9993f, 0.99935f, 0.9994f, 1.0005f, 1f, 0.9996f, 1.0002f)
        rates.forEachIndexed { i, r -> f.add(i * 500L, 10_000 + i * 500L + if (i % 2 == 0) 150 else -150, r, true, "t") }
        assertEquals("jitter still averaged out", 10_000 + 3_500.0, f.estimate(3_500)!!, 40.0)
    }

    @Test
    fun frozenReadingsAreSkipped() {
        // After a seek the position can stand still for a second while audio starts: those readings are stale.
        val f = PositionFilter()
        for (i in 0..3) f.add(i * 500L, 20_000 + i * 500L, 1f, true, "t")
        // Frozen for 1.5 s (as seen after a seek on the emulator): the same reading again and again.
        for (t in listOf(2_000L, 2_500L, 3_000L)) f.add(t, 21_500, 1f, true, "t")
        assertEquals(20_000 + 3_000.0, f.estimate(3_000)!!, 20.0)
    }
}
