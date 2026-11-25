package fr.spotifeur.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeCurveTest {
    @Test
    fun equalPowerHoldsConstantPowerThroughTheBlend() {
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val out = Crossfade.gainOut(t); val into = Crossfade.gainIn(t)
            assertEquals("power at $t", 1f, out * out + into * into, 1e-5f)
        }
    }

    @Test
    fun theBlendStartsOnTheOutgoingTrackAndEndsOnTheIncomingOne() {
        assertEquals(1f, Crossfade.gainOut(0f), 1e-6f); assertEquals(0f, Crossfade.gainIn(0f), 1e-6f)
        assertEquals(0f, Crossfade.gainOut(1f), 1e-6f); assertEquals(1f, Crossfade.gainIn(1f), 1e-6f)
        assertEquals(Crossfade.gainOut(0.5f), Crossfade.gainIn(0.5f), 1e-6f)
    }
}
