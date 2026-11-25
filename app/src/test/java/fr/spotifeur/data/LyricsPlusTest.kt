package fr.spotifeur.data

import fr.spotifeur.data.repository.lyricsPlusToLrc
import fr.spotifeur.utils.LyricsUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsPlusTest {
    // Shape of the live answer (lyricsplus.binimum.org, Blinding Lights, 2026-11-25), trimmed.
    private val answer = """{"type":"Word","lyrics":[
        {"time":30189,"duration":2000,"text":"I've been tryna call","syllabus":[
            {"time":30189,"duration":207,"text":"I've "},{"time":30396,"duration":246,"text":"been "},
            {"time":30642,"duration":300,"text":"tryna "},{"time":30942,"duration":500,"text":"call"}]},
        {"time":34000,"duration":900,"text":"(ooh)","syllabus":[{"time":34000,"duration":900,"text":"(ooh)","isBackground":true}]}]}"""

    @Test
    fun wordTimingBecomesEnhancedLrcTheParserReads() {
        val lrc = lyricsPlusToLrc(JSONObject(answer))!!
        assertEquals("[00:30.18]<00:30.18>I've <00:30.39>been <00:30.64>tryna <00:30.94>call\n", lrc)
        val line = LyricsUtils.parseLyrics(lrc).synced!!.single()
        assertEquals(30180, line.time)
        assertEquals(4, line.words!!.size)
        assertEquals(30390, line.words!![1].time)
    }

    @Test
    fun aLineOnlyAnswerAddsNothing() {
        assertNull(lyricsPlusToLrc(JSONObject("""{"type":"Line","lyrics":[{"time":1000,"text":"hello"}]}""")))
    }
}
