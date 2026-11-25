package fr.spotifeur.playback.dsp

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import fr.spotifeur.data.preferences.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Headphone correction from AutoEq (github.com/jaakkopasanen/AutoEq, MIT): the index of measured headphones is read
 * once and kept on the device; the connected headphones are matched by name and their parametric profile is applied.
 */
object AutoEq {
    private const val RAW = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    /** Measurement sources, most trusted first. */
    private val SOURCES = listOf("oratory1990", "crinacle", "Rtings", "Innerfidelity", "Headphone.com Legacy")
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private var index: List<Entry>? = null

    data class Entry(val name: String, val path: String, val source: String)
    data class Profile(val headphone: String, val curve: EqCurve)

    internal fun parseIndex(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
        val match = LINE.find(line) ?: return@mapNotNull null
        Entry(match.groupValues[1], match.groupValues[2].removePrefix("./"), match.groupValues[3])
    }.toList()

    internal fun normalize(name: String) = name.lowercase().replace("samsung", "").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** The measured headphone whose name the device name holds (or the reverse), the longest such name, best source. */
    internal fun match(deviceName: String, entries: List<Entry>): Entry? {
        val wanted = normalize(deviceName).takeIf { it.length >= 3 } ?: return null
        return entries.filter { val n = normalize(it.name); n.length >= 3 && (wanted == n || wanted.contains(n) || n.contains(wanted)) }
            .sortedWith(compareByDescending<Entry> { normalize(it.name) == wanted }.thenByDescending { it.name.length }
                .thenBy { SOURCES.indexOf(it.source).let { i -> if (i < 0) SOURCES.size else i } })
            .firstOrNull()
    }

    internal fun parseProfile(text: String): EqCurve {
        var preamp = 0.0
        val bands = text.lineSequence().mapNotNull { line ->
            PREAMP.find(line)?.let { preamp = it.groupValues[1].toDouble(); return@mapNotNull null }
            val f = FILTER.find(line) ?: return@mapNotNull null
            val type = when (f.groupValues[1]) { "PK" -> EqBand.Type.PEAK; "LSC", "LS" -> EqBand.Type.LOW_SHELF; "HSC", "HS" -> EqBand.Type.HIGH_SHELF; else -> return@mapNotNull null }
            EqBand(type, f.groupValues[2].toDouble(), f.groupValues[3].toDouble(), f.groupValues[4].toDouble())
        }.toList()
        return EqCurve(preamp, bands)
    }

    /** Blocking. The profile for [deviceName], or null when no measured headphone matches or the network is down. */
    fun profileFor(context: Context, deviceName: String): Profile? = runCatching {
        val entries = index ?: loadIndex(context).also { index = it }
        val entry = match(deviceName, entries) ?: return null
        val cache = File(context.cacheDir, "autoeq/" + entry.path.hashCode() + ".txt")
        val text = if (cache.isFile) cache.readText() else {
            val base = entry.path.substringAfterLast('/')
            get(RAW + entry.path + "/" + base + "%20ParametricEQ.txt").also { cache.parentFile?.mkdirs(); cache.writeText(it) }
        }
        Profile(entry.name, parseProfile(text))
    }.getOrNull()

    private fun loadIndex(context: Context): List<Entry> {
        val file = File(context.filesDir, "autoeq-index.md")
        // Kept a month: new headphones appear in the index, old ones keep their path.
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > 30L * 24 * 3600 * 1000) file.writeText(get(RAW + "INDEX.md"))
        return parseIndex(file.readText())
    }

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).build()).execute().use {
        check(it.isSuccessful) { "HTTP ${it.code}" }; it.body?.string().orEmpty()
    }

    private val LINE = Regex("""^- \[(.+?)]\((\./[^)]+)\) by (\S+(?: \S+)?)""")
    private val PREAMP = Regex("""Preamp:\s*(-?[0-9.]+)\s*dB""")
    private val FILTER = Regex("""Filter \d+:\s*ON\s+(PK|LSC|HSC|LS|HS)\s+Fc\s+([0-9.]+)\s*Hz\s+Gain\s+(-?[0-9.]+)\s*dB\s+Q\s+([0-9.]+)""")
}

/** What the sound settings make of the audio: the tone curve, the headphone profile and the spatial amount. */
object SoundRuntime {
    private val mutableHeadphone = MutableStateFlow<String?>(null)
    /** The headphones the correction is applied for, or null when none is connected or matched. */
    val headphone = mutableHeadphone.asStateFlow()
    @Volatile private var profile: AutoEq.Profile? = null

    /** The user's tone: bass and treble shelves, and "Dynamic", a loudness contour (deep low end, clearer top). */
    fun toneCurve(s: AppSettings): EqCurve {
        val d = s.toneDynamic.coerceIn(0, 6).toDouble()
        val bands = listOf(
            EqBand(EqBand.Type.LOW_SHELF, 110.0, s.toneBass.coerceIn(-6, 6).toDouble(), 0.7),
            EqBand(EqBand.Type.HIGH_SHELF, 8000.0, s.toneTreble.coerceIn(-6, 6).toDouble(), 0.7),
            EqBand(EqBand.Type.LOW_SHELF, 60.0, d * 0.8, 0.7),
            EqBand(EqBand.Type.PEAK, 400.0, -d * 0.5, 1.0),
            EqBand(EqBand.Type.PEAK, 3000.0, d * 0.4, 1.2),
        )
        // Room for the boosts; the limiter after the chain catches what is left.
        val boost = bands.filter { it.gainDb > 0 }.maxOfOrNull { it.gainDb } ?: 0.0
        return EqCurve(-boost * 0.5, bands)
    }

    fun curve(s: AppSettings): EqCurve = toneCurve(s) + (profile?.takeIf { s.headphoneEq }?.curve ?: EqCurve.FLAT)

    fun spatialAmount(s: AppSettings): Float = if (s.spatialAudio) 1f else 0f

    /** Blocking: finds the connected headphones' correction. Returns true when it changed. */
    suspend fun refreshHeadphones(context: Context): Boolean = withContext(Dispatchers.IO) {
        val audio = context.getSystemService(AudioManager::class.java)
        val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADPHONES }
        val name = device?.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val found = name?.let { AutoEq.profileFor(context, it) }
        val changed = found?.headphone != profile?.headphone
        profile = found
        mutableHeadphone.value = found?.headphone
        android.util.Log.i("SpotifeurSound", "headphones=$name profile=${found?.headphone} bands=${found?.curve?.bands?.size}")
        changed
    }

    private val HEADPHONES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    )
}
