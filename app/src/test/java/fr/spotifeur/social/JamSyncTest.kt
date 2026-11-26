package fr.spotifeur.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JamSyncTest {
    @Test
    fun theOffsetComesFromTheFastestRoundTrip() {
        val clock = JamClock()
        // Host clock is 10_000 ms ahead. Slow, lopsided trips give poor estimates; the 80 ms one is near the truth.
        clock.sample(sentAt = 0, hostTime = 10_000 + 600, receivedAt = 900)
        clock.sample(sentAt = 1_000, hostTime = 11_000 + 40, receivedAt = 1_080)
        clock.sample(sentAt = 2_000, hostTime = 12_000 + 100, receivedAt = 2_700)
        assertEquals(10_000.0, clock.offsetMs!!.toDouble(), 5.0)
        assertEquals(80L, clock.bestRoundTripMs)
        assertEquals(15_000L, clock.hostTime(localNow = 5_000))
    }

    @Test
    fun oldSamplesAgeOut() {
        val clock = JamClock(window = 3)
        clock.sample(0, 10_040, 80)
        repeat(3) { clock.sample(1_000L * (it + 1), 21_000L + 1_000 * it + 150, 1_000L * (it + 1) + 300) }
        assertEquals("the 80 ms sample left the window", 300L, clock.bestRoundTripMs)
    }

    @Test
    fun deadbandIsQuiet() {
        assertEquals(DriftAction.None, JamDrift.decide(listOf(10L, -12L, 8L, 14L, -9L)))
    }

    @Test
    fun trimStaysInaudible() {
        // Ahead: a touch slower; behind: a touch faster; never beyond 0.1%.
        val ahead = JamDrift.decide(List(5) { 140L }) as DriftAction.Trim
        assertTrue("ahead slows down: ${ahead.rate}", ahead.rate >= 0.999f && ahead.rate < 1f)
        val behind = JamDrift.decide(List(5) { -140L }) as DriftAction.Trim
        assertTrue("behind speeds up: ${behind.rate}", behind.rate > 1f && behind.rate <= 1.001f)
    }

    @Test
    fun oneOutlierDoesNotSeek() {
        // A single bad reading (a late position, a hiccup) must not make anyone jump.
        val action = JamDrift.decide(listOf(20L, 25L, 900L, 22L, 18L))
        assertTrue("got $action", action is DriftAction.Trim || action == DriftAction.None)
    }

    @Test
    fun realGapSeeks() {
        assertEquals(DriftAction.Seek, JamDrift.decide(listOf(400L, 410L, 395L, 405L, 402L)))
    }

    @Test
    fun theHostPositionAdvancesOnlyWhilePlaying() {
        val playing = JamState("s1", "spotify:track:x", positionMs = 30_000, atHostTime = 100_000, playing = true, startAtHostTime = null)
        assertEquals(32_500L, playing.positionAt(hostNow = 102_500))
        assertEquals(30_000L, playing.copy(playing = false).positionAt(hostNow = 102_500))
        // A scheduled start: nothing before the moment, then from the start position.
        val scheduled = playing.copy(positionMs = 0, startAtHostTime = 101_500)
        assertEquals(0L, scheduled.positionAt(hostNow = 101_000))
        assertEquals(500L, scheduled.positionAt(hostNow = 102_000))
    }

    @Test
    fun messagesOfASessionNotJoinedAreIgnored() {
        val guard = JamGuard()
        assertTrue(!guard.accepts("s1", from = "host"))
        guard.joined("s1", host = "host", members = setOf("host", "me"))
        assertTrue(guard.accepts("s1", from = "host"))
        assertTrue("only members", !guard.accepts("s1", from = "stranger"))
        assertTrue("another session", !guard.accepts("s2", from = "host"))
        guard.left()
        assertTrue(!guard.accepts("s1", from = "host"))
    }

    @Test
    fun aSeekNeedsSeveralReadings() {
        // Right after a reset one noisy reading must not trigger a seek (that chained seeks on a real guest).
        assertFalse(JamDrift.decide(listOf(400L)) == DriftAction.Seek)
        assertFalse(JamDrift.decide(listOf(400L, 410L)) == DriftAction.Seek)
        assertEquals(DriftAction.Seek, JamDrift.decide(listOf(400L, 410L, 395L)))
    }
}
