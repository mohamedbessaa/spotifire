package fr.spotifeur.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import fr.spotifeur.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates from GitHub Releases, the way BitChord lands them (adapted from BitChord's AppUpdateChecker, GPLv3):
 * once per launch the repository's latest release is compared with the running version; the release's APK is
 * downloaded inside the app and handed to the system installer. Nothing is notified: the app shows a popup.
 */
object AppUpdateChecker {
    data class UpdateInfo(val version: String, val releaseUrl: String, val apkUrl: String?, val notes: String?)

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private const val CACHE_SUBDIR = "updates"
    private val latestReleaseUrl = "https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest"
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    private val mutableAvailable = MutableStateFlow<UpdateInfo?>(null)
    val available = mutableAvailable.asStateFlow()
    private val mutableDownload = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = mutableDownload.asStateFlow()
    @Volatile private var cancelled = false
    /** Raised by a manual check that finds an update, so the popup shows again even after Remind Me Later. */
    private val mutableOffers = MutableStateFlow(0)
    val offers = mutableOffers.asStateFlow()

    enum class CheckResult { UP_TO_DATE, AVAILABLE, FAILED }

    /** The manual check from Settings: same as at launch, and an update found is offered right away. */
    suspend fun checkNow(): CheckResult = check().also { if (it == CheckResult.AVAILABLE) mutableOffers.value++ }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        var result = CheckResult.FAILED
        runCatching {
            val body = http.newCall(Request.Builder().url(latestReleaseUrl).header("Accept", "application/vnd.github+json").build())
                .execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching
            val release = JSONObject(body)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@runCatching
            val tag = release.optString("tag_name").takeIf(String::isNotBlank) ?: return@runCatching
            val latest = tag.removePrefix("v")
            result = CheckResult.UP_TO_DATE
            if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                result = CheckResult.AVAILABLE
                mutableAvailable.value = UpdateInfo(latest, release.optString("html_url"), apkAssetUrl(release, Build.SUPPORTED_ABIS.toList()),
                    release.optString("body").takeIf(String::isNotBlank))
            }
        }
        result
    }

    /** A downloaded update is only "Install now" for the session that downloaded it; the next launch starts clean. */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * The release's APK for this app: assets named with [BuildConfig.UPDATE_APK_PREFIX], the universal one (no
     * "_abi" suffix) first, else the split for this device's ABI; only fully uploaded files.
     */
    internal fun apkAssetUrl(release: JSONObject, abis: List<String>): String? {
        val assets = release.optJSONArray("assets") ?: return null
        val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.mapNotNull { asset ->
            val name = asset.optString("name"); val url = asset.optString("browser_download_url")
            val ours = name.startsWith(BuildConfig.UPDATE_APK_PREFIX, ignoreCase = true) && name.endsWith(".apk", ignoreCase = true) &&
                asset.optString("state") == "uploaded" && url.startsWith("https://")
            if (ours) name.dropLast(4) to url else null
        }
        return apks.firstOrNull { (name, _) -> '_' !in name }?.second
            ?: abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { (name, _) -> name.endsWith("_$abi") }?.second }
    }

    /** Streams the update's APK into the cache, with progress in [download]. */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = mutableAvailable.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        cancelled = false
        mutableDownload.value = DownloadState.Downloading(0f)
        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "spotifire-${info.version}.apk")
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
                val body = response.body ?: error("Empty download")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            if (cancelled) { mutableDownload.value = DownloadState.Idle; return@withContext }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            done += read
                            total?.let { mutableDownload.value = DownloadState.Downloading((done.toFloat() / it).coerceIn(0f, 1f)) }
                        }
                    }
                }
            }
            mutableDownload.value = DownloadState.Ready(target)
        }.onFailure { mutableDownload.value = if (cancelled) DownloadState.Idle else DownloadState.Failed(it.message ?: "Download failed") }
    }

    fun cancelDownload() { cancelled = true }
    fun resetDownload() { mutableDownload.value = DownloadState.Idle }

    /**
     * Hands the APK to the system installer. Android asks once per app to allow installing apps ("install unknown
     * apps"); without it the installer does nothing, so that switch is opened first and Install is tapped again.
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val opened = runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private data class ParsedVersion(val parts: List<Int>, val preRelease: Boolean)
    private fun parse(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Numeric, dot by dot ("1.10" > "1.9"); a "-beta" build is older than the plain release of the same numbers. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest); val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }; val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}
