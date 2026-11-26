package fr.spotifeur.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking to join a friend's music: only friends, one request each, gone after two minutes, not sent in bursts. */
class JamRequestTest {
    private var clock = 1_000_000L
    private val requests = JoinRequests { clock }

    @Test
    fun strangerRequestIgnored() {
        assertFalse(requests.receive("stranger", isFriend = false))
        assertTrue(requests.list().isEmpty())
    }

    @Test
    fun repeatCollapses() {
        assertTrue(requests.receive("ana", isFriend = true))
        clock += 5_000
        assertFalse("a repeat is not a new request", requests.receive("ana", isFriend = true))
        assertEquals(listOf("ana"), requests.list().map { it.from })
    }

    @Test
    fun requestExpires() {
        requests.receive("ana", isFriend = true)
        clock += 2 * 60_000 + 1_000
        assertTrue(requests.list().isEmpty())
    }

    @Test
    fun askingIsPaced() {
        val pace = AskPace { clock }
        assertTrue(pace.mayAsk("ana"))
        clock += 10_000
        assertFalse("a second ask within 30 s waits", pace.mayAsk("ana"))
        assertTrue("another friend is not held up", pace.mayAsk("ben"))
        clock += 21_000
        assertTrue(pace.mayAsk("ana"))
    }

    @Test
    fun expiredRequestCannotBeAccepted() {
        requests.receive("ana", isFriend = true)
        clock += 2 * 60_000 + 1_000
        assertFalse("too late to let in", requests.take("ana"))
        requests.receive("ben", isFriend = true)
        assertTrue(requests.take("ben"))
        assertFalse("taken once", requests.take("ben"))
    }

    @Test
    fun anAskIsRememberedForTheInvitation() {
        // The friend who was asked answers with an invitation; it is joined without a second tap, for two minutes.
        val pace = AskPace { clock }
        pace.mayAsk("ana")
        clock += 60_000
        assertTrue(pace.askedRecently("ana"))
        assertFalse(pace.askedRecently("ben"))
        clock += 61_000
        assertFalse(pace.askedRecently("ana"))
    }

    @Test
    fun anOldRequestDeliveredAgainIsIgnored() {
        // Relays deliver old messages again after a restart: a request sent hours ago must not pop up as new.
        assertFalse(requests.receive("ana", isFriend = true, sentAtMs = clock - 3 * 60 * 60_000L))
        assertTrue(requests.list().isEmpty())
        assertTrue("a fresh one still counts", requests.receive("ana", isFriend = true, sentAtMs = clock - 5_000))
    }
}
