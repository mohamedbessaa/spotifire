package fr.spotifeur.social

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * How a Friends message travels inside a Nostr event: its JSON, gzipped and base64-encoded, split into numbered parts
 * when it is bigger than relays accept (a 2,000-song playlist is about 600 kB of JSON). Each part is the content of its
 * own encrypted event; [Assembler] puts them back together in any order and ignores repeats.
 */
object Envelope {
    /** Relays commonly cap events around 64 kB; the gift wrap's encryption and encoding add about 40% on top. */
    const val MAX_PART_CHARS = 40_000
    private const val VERSION = 1

    fun encode(id: String, message: JSONObject): List<String> {
        val packed = Base64.getEncoder().encodeToString(gzip(message.toString().toByteArray()))
        val chunks = packed.chunked(MAX_PART_CHARS - 200)
        return chunks.mapIndexed { index, chunk ->
            JSONObject().put("v", VERSION).put("id", id).put("i", index).put("n", chunks.size).put("d", chunk).toString()
        }
    }

    /** Collects parts; returns a message once, when its last missing part arrives. */
    class Assembler(private val remember: Int = 2000) {
        private val pending = HashMap<String, Array<String?>>()
        private val done = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > remember
        }

        @Synchronized fun accept(content: String): JSONObject? {
            val part = runCatching { JSONObject(content) }.getOrNull() ?: return null
            if (part.optInt("v") != VERSION) return null
            val id = part.optString("id").takeIf(String::isNotEmpty) ?: return null
            if (done.containsKey(id)) return null
            val count = part.optInt("n", 0).takeIf { it in 1..512 } ?: return null
            val index = part.optInt("i", -1).takeIf { it in 0 until count } ?: return null
            val slots = pending.getOrPut(id) { arrayOfNulls(count) }
            if (slots.size != count) return null
            slots[index] = part.optString("d")
            if (slots.any { it == null }) return null
            pending.remove(id)
            done[id] = true
            return runCatching { JSONObject(String(gunzip(Base64.getDecoder().decode(slots.joinToString(""))))) }.getOrNull()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
