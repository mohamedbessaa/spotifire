package fr.spotifeur.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Everyone in a Jam can steer it: commands travel as JSON, are applied once, and the last one wins. */
class JamCommandsTest {
    private val track = JSONObject().put("id", "spotify:track:abc").put("title", "Song").put("subtitle", "Artist")

    /** A player that records what it was told, with a simple playing flag. */
    private class FakePlayer : JamPlayer {
        val calls = mutableListOf<String>()
        var playing = true
        override fun now() = JamPlayer.Now(JSONObject().put("id", "spotify:track:abc"), 1000, playing, readAt = 0)
        override fun upNext() = emptyList<JSONObject>()
        override fun playQueue(tracks: List<JSONObject>, positionMs: Long, start: Boolean) {
            calls += "playQueue ${tracks.first().optString("id")}" + if (tracks.size > 1) " +${tracks.size - 1}" else ""; playing = start }
        override fun pause() { calls += "pause"; playing = false }
        override fun resume() { calls += "resume"; playing = true }
        override fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override fun trim(rate: Float) {}
        override fun addToQueue(track: JSONObject) { calls += "add ${track.optString("id")}" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun saveQueue() {}
        override fun restoreQueue() {}
        override fun intercept(control: GuestControl?) {}
    }

    @Test
    fun commandsRoundTrip() {
        val all = listOf(JamCommand.Play, JamCommand.Pause, JamCommand.Seek(83_000), JamCommand.Next, JamCommand.Previous,
            JamCommand.PlayTrack(track), JamCommand.Add(track))
        for (command in all) {
            val back = JamCommand.parse(JSONObject(command.toJson().toString()))
            assertEquals(command.toJson().toString(), back?.toJson()?.toString())
        }
    }

    @Test
    fun unknownIsIgnored() {
        assertNull(JamCommand.parse(JSONObject("""{"type":"cmd","do":"format-disk"}""")))
        assertNull(JamCommand.parse(JSONObject("""{"type":"cmd","do":"seek"}""")))
    }

    @Test
    fun onlyMembersCommand() {
        val guard = JamGuard().apply { joined("s1", "host", setOf("host", "a", "b")) }
        assertTrue(guard.accepts("s1", "a"))
        assertFalse("a stranger's command is dropped", guard.accepts("s1", "stranger"))
        assertFalse("another session's command is dropped", guard.accepts("s2", "a"))
    }

    @Test
    fun duplicateAppliedOnce() {
        val player = FakePlayer()
        val applier = JamCommandApplier()
        val body = JamCommand.Next.toJson().put("cid", "c-1")
        assertTrue(applier.apply(player, "a", body))
        assertFalse("the same command id twice is applied once", applier.apply(player, "a", body))
        assertEquals(listOf("next"), player.calls)
    }

    @Test
    fun lastOneWins() {
        val player = FakePlayer()
        val applier = JamCommandApplier()
        applier.apply(player, "a", JamCommand.Pause.toJson().put("cid", "1"))
        applier.apply(player, "b", JamCommand.Play.toJson().put("cid", "2"))
        assertTrue(player.playing)
        assertEquals("b", applier.lastAction?.first)
        assertEquals("played", applier.lastAction?.second)
    }

    @Test
    fun playingASongKeepsWhatFollows() {
        // A guest who taps track 3 of an album means "play from here", not "play this one song and stop".
        val t2 = JSONObject().put("id", "spotify:track:def").put("title", "Two")
        val t3 = JSONObject().put("id", "ytm:track:ghi").put("title", "Three")
        val command = JamCommand.PlayTrack(track, listOf(t2, t3))
        val back = JamCommand.parse(JSONObject(command.toJson().toString())) as JamCommand.PlayTrack
        assertEquals(listOf("spotify:track:def", "ytm:track:ghi"), back.next.map { it.optString("id") })
        val player = FakePlayer()
        JamCommandApplier().apply(player, "a", command.toJson().put("cid", "x"))
        assertEquals(listOf("playQueue spotify:track:abc +2"), player.calls)
    }
}
