package fr.spotifeur.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun versionsCompareByNumberAndABetaIsOlderThanItsRelease() {
        assertTrue(AppUpdateChecker.isNewer("1.10", "1.9"))
        assertTrue(AppUpdateChecker.isNewer("1.0.1", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewer("0.9", "1.0.0"))
        assertTrue(AppUpdateChecker.isNewer("1.1", "1.1-beta2"))
        assertFalse(AppUpdateChecker.isNewer("1.1-beta3", "1.1"))
    }

    private fun asset(name: String, state: String = "uploaded") =
        JSONObject().put("name", name).put("state", state).put("browser_download_url", "https://github.com/o/r/releases/download/v1/$name")

    @Test
    fun theUniversalApkOfThisAppIsPicked() {
        val release = JSONObject().put("assets", JSONArray(listOf(asset("notes.txt"), asset("spotifire-v1.1_arm64-v8a.apk"),
            asset("spotifire-v1.1.apk"), asset("other-app-v1.1.apk"))))
        assertEquals("https://github.com/o/r/releases/download/v1/spotifire-v1.1.apk", AppUpdateChecker.apkAssetUrl(release, listOf("arm64-v8a")))
    }

    @Test
    fun withoutAUniversalApkTheSplitForThisDeviceIsPickedAndUnfinishedUploadsAreIgnored() {
        val release = JSONObject().put("assets", JSONArray(listOf(asset("spotifire-v1.1_x86_64.apk"), asset("spotifire-v1.1_arm64-v8a.apk"),
            asset("spotifire-v1.1.apk", state = "new"))))
        assertEquals("https://github.com/o/r/releases/download/v1/spotifire-v1.1_arm64-v8a.apk", AppUpdateChecker.apkAssetUrl(release, listOf("arm64-v8a")))
        assertNull(AppUpdateChecker.apkAssetUrl(JSONObject().put("assets", JSONArray()), listOf("arm64-v8a")))
    }
}
