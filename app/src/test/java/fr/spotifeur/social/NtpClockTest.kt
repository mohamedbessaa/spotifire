package fr.spotifeur.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NtpClockTest {
    @Test
    fun theOffsetAndRoundTripFollowTheNtpFormula() {
        // Sent at 1000 local, server got it at 6000 and answered at 6002 (its clock), back at 1030 local:
        // round trip 28 ms, server clock 4986 ms ahead.
        val s = Ntp.sample(t0 = 1000, t1 = 6000, t2 = 6002, t3 = 1030)
        assertEquals(28L, s.roundTrip)
        assertEquals(4986L, s.offset)
    }

    @Test
    fun aServerTimestampIsReadFromItsPacket() {
        val unixMs = 1_795_641_538_250L
        val packet = ByteArray(48)
        Ntp.writeTimestamp(packet, 40, unixMs)
        assertEquals(unixMs.toDouble(), Ntp.readTimestamp(packet, 40).toDouble(), 1.0)
    }

    @Test
    fun theFastestSampleWinsAndBadAnswersAreRefused() {
        val best = Ntp.best(listOf(Ntp.Sample(80, 5000), Ntp.Sample(12, 4990), Ntp.Sample(40, 5020)))
        assertEquals(4990L, best!!.offset)
        assertNull(Ntp.best(emptyList()))
        // A reply that is not a server answer (mode 4) or has no time is ignored.
        assertNull(Ntp.parse(ByteArray(48), t0 = 0, t3 = 10))
        val reply = ByteArray(48).also { it[0] = 0x24; it[1] = 2; Ntp.writeTimestamp(it, 32, 5000); Ntp.writeTimestamp(it, 40, 5001) }
        assertNotNull(Ntp.parse(reply, t0 = 0, t3 = 10))
    }
}
