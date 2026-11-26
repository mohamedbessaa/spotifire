package fr.spotifeur.data

import fr.spotifeur.data.sources.Copy
import fr.spotifeur.data.sources.CopyResolver
import fr.spotifeur.data.sources.MonochromeTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import fr.spotifeur.data.sources.MonochromeGate
import org.junit.Test

class MonochromeTracksTest {
    // Shape of the live answer to /search/tracks?q=daft%20punk%20get%20lucky (2026-11-25), trimmed.
    private val response = """{"tracks":[
        {"id":"155142501534011392","title":"Get Lucky (feat. Pharrell Williams and Nile Rodgers)","playable":true,"duration":369626,"isrc":"USQX91300108"},
        {"id":"158233189067153408","title":"Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]","playable":true,"duration":247632,"isrc":"USQX91300809"},
        {"id":"9","title":"Get Lucky","playable":false,"duration":369000,"isrc":"USQX91300108"}]}"""

    @Test
    fun onlyThePlayableTrackWithTheSameIsrcIsTaken() {
        val found = MonochromeTracks.pick("usqx91300108", 369_000, MonochromeTracks.parse(response))
        assertEquals("155142501534011392", found?.id)
        assertEquals(369_626, found?.durationMs)
    }

    @Test
    fun aDifferentIsrcIsNeverTakenEvenWithTheSameTitle() {
        assertNull(MonochromeTracks.pick("USQX91399999", 369_000, MonochromeTracks.parse(response)))
    }

    @Test
    fun choicesRoundTripSoATrackKeepsItsCopy() {
        listOf(Copy.Monochrome("155142501534011392"), Copy.YouTube("5NV6Rdv1a3I")).forEach {
            assertEquals(it, CopyResolver.decode(CopyResolver.encode(it)))
        }
        assertNull(CopyResolver.decode(""))
    }

    @Test
    fun losslessUrlAsksForFlac() {
        assertEquals("https://tracks.monochrome.st/track/7?quality=LOSSLESS", MonochromeTracks.streamUrl("7", lossless = true))
        assertEquals("https://tracks.monochrome.st/track/7", MonochromeTracks.streamUrl("7"))
    }

    private var clock = 0L
    private val gate = MonochromeGate { clock }

    @Test
    fun gateClosesAfterARefusal() {
        assertTrue(gate.open())
        gate.refused(null)
        clock += 1_000; assertFalse(gate.open())
        clock += 1_100; assertTrue(gate.open())
    }

    @Test
    fun backOffDoubles() {
        gate.refused(null); gate.refused(null)
        clock += 3_000; assertFalse(gate.open())
        clock += 1_100; assertTrue(gate.open())
    }

    @Test
    fun retryAfterWins() {
        gate.refused(10)
        clock += 9_000; assertFalse(gate.open())
        clock += 1_100; assertTrue(gate.open())
    }

    @Test
    fun successResets() {
        gate.refused(null); gate.refused(null); gate.refused(null)
        gate.served()
        assertTrue(gate.open())
        gate.refused(null)
        clock += 2_100; assertTrue("back to the first step after a success", gate.open())
    }
}
