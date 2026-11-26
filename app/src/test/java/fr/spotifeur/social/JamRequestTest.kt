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
}
