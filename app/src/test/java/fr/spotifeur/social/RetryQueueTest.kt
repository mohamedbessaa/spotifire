package fr.spotifeur.social

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class RetryQueueTest {
    private fun queue() = RetryQueue(Files.createTempDirectory("retry").resolve("retry.json").toFile())

    @Test
    fun aFailedSendStaysUntilItGoesThroughEvenAfterARestart() = runBlocking {
        val file = Files.createTempDirectory("retry").resolve("retry.json").toFile()
        RetryQueue(file).add("m1", listOf("bob"), JSONObject().put("type", "share"), pow = 0, at = 100)
        // The app was killed: a new queue reads the same file.
        val again = RetryQueue(file)
        assertEquals(1, again.drain(now = 200) { false })
        assertEquals("still waiting", 1, again.size())
        assertEquals(0, again.drain(now = 300) { it.id == "m1" })
        assertEquals(0, again.size())
    }

    @Test
    fun theSameIdQueuedTwiceIsSentOnceAndOldEntriesExpire() = runBlocking {
        val q = queue()
        q.add("a", listOf("bob"), JSONObject(), 0, at = 0)
        q.add("a", listOf("bob"), JSONObject(), 0, at = 10)
        q.add("old", listOf("bob"), JSONObject(), 0, at = 0)
        assertEquals(2, q.size())
        var tried = 0
        q.drain(now = RetryQueue.KEEP_FOR + 5) { tried++; false }
        assertEquals("'old' expired without a try, 'a' was tried", 1, tried)
        assertEquals(1, q.size())
    }
}
