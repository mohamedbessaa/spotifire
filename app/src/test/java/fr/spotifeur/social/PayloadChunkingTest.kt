package fr.spotifeur.social

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadChunkingTest {
    private fun playlist(tracks: Int) = JSONObject().put("type", "share").put("title", "Road trip").put("tracks", JSONArray().apply {
        repeat(tracks) { i ->
            put(JSONObject().put("id", "spotify:track:${"%022d".format(i)}").put("title", "Song number $i with a long enough title")
                .put("artists", "Artist $i, Featured ${i % 17}").put("album", "Album ${i / 12}").put("durationMs", 180_000 + i)
                .put("isrc", "USRC1%07d".format(i)).put("image", "https://i.scdn.co/image/ab67616d0000b273${"%024x".format(i)}"))
        }
    })

    @Test
    fun aSmallMessageIsOnePart() {
        val parts = Envelope.encode("m1", JSONObject().put("type", "receipt").put("state", "seen"))
        assertEquals(1, parts.size)
        assertEquals("seen", Envelope.Assembler().accept(parts.single())!!.getString("state"))
    }

    @Test
    fun aHugePlaylistSplitsUnderTheLimitAndReassemblesInAnyOrder() {
        val original = playlist(2000)
        val parts = Envelope.encode("m2", original)
        assertTrue("several parts", parts.size > 1)
        assertTrue("each part under ${Envelope.MAX_PART_CHARS}", parts.all { it.length <= Envelope.MAX_PART_CHARS })
        val assembler = Envelope.Assembler()
        val shuffled = parts.shuffled(java.util.Random(7))
        val results = shuffled.map { assembler.accept(it) }
        assertEquals("only the last part completes it", 1, results.count { it != null })
        assertEquals(original.toString(), results.last { it != null }.toString())
    }

    @Test
    fun aRepeatedPartOrMessageIsIgnored() {
        val parts = Envelope.encode("m3", playlist(2000))
        val assembler = Envelope.Assembler()
        parts.forEach { assembler.accept(it); assembler.accept(it) }
        // Everything sent again after completion produces nothing new.
        assertTrue(parts.map { assembler.accept(it) }.all { it == null })
    }

    @Test
    fun garbageIsIgnored() {
        assertNull(Envelope.Assembler().accept("not an envelope"))
    }
}
