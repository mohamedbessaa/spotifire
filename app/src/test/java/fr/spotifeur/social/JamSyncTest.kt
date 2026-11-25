package fr.spotifeur.social

import org.junit.Assert.assertEquals
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
    fun driftIsLeftAloneNudgedInProportionOrJumped() {
        assertEquals(DriftAction.None, JamDrift.decide(expectedMs = 60_000, actualMs = 60_015))
        // 30 ms ahead: slow down a little, closing the gap in about 2.5 s.
        assertEquals(0.988f, (JamDrift.decide(expectedMs = 60_000, actualMs = 60_030) as DriftAction.Speed).rate, 0.0005f)
        assertEquals(DriftAction.Speed(1.03f), JamDrift.decide(expectedMs = 60_000, actualMs = 59_900)) // behind: faster
        assertEquals(DriftAction.Speed(0.97f), JamDrift.decide(expectedMs = 60_000, actualMs = 60_200)) // ahead: slower
        assertEquals(DriftAction.Seek(60_000), JamDrift.decide(expectedMs = 60_000, actualMs = 60_400))
        assertEquals(DriftAction.Seek(60_000), JamDrift.decide(expectedMs = 60_000, actualMs = 59_000))
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
}
