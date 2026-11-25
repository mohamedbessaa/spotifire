package fr.spotifeur.data

import fr.spotifeur.data.preferences.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsPlaybackTest {
    @Test fun defaults() {
        val d = AppSettings()
        assertFalse(d.skipSilence)
        assertEquals(1f, d.playbackSpeed, 0f)
        assertTrue(d.keepPitch)
    }

    @Test fun playbackFieldsRoundTrip() {
        val custom = AppSettings(skipSilence = true, playbackSpeed = 1.25f, keepPitch = false)
        val back = AppSettings.fromJson(custom.toJson())
        assertEquals(custom, back)
        assertTrue(back.skipSilence)
        assertEquals(1.25f, back.playbackSpeed, 0f)
        assertFalse(back.keepPitch)
    }

    @Test fun oldLanguageKeyIsIgnored() {
        val old = AppSettings.fromJson(JSONObject().put("language", "fr").put("showScrollbar", false))
        assertFalse(old.showScrollbar)
        assertEquals(AppSettings().copy(showScrollbar = false), old)
    }
}
