package fr.spotifeur.social

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Messages that reached no relay, kept in a file so they survive the app being killed, and sent again until they go
 * through or are [KEEP_FOR] old. One entry per message id.
 */
class RetryQueue(private val file: File) {
    data class Entry(val id: String, val to: List<String>, val body: JSONObject, val pow: Int, val at: Long)

    private val lock = Mutex()

    suspend fun add(id: String, to: List<String>, body: JSONObject, pow: Int, at: Long) = lock.withLock {
        write(read().filterNot { it.id == id } + Entry(id, to, body, pow, at))
    }

    suspend fun size() = lock.withLock { read().size }

    /** Tries every entry with [send]; drops what went through or expired. Returns how many are still waiting. */
    suspend fun drain(now: Long, send: suspend (Entry) -> Boolean): Int {
        val entries = lock.withLock { read() }
        val finished = mutableSetOf<String>()
        for (entry in entries) {
            if (now - entry.at > KEEP_FOR || runCatching { send(entry) }.getOrDefault(false)) finished += entry.id
        }
        return lock.withLock { read().filterNot { it.id in finished }.also(::write).size }
    }

    private fun read(): List<Entry> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { array.getJSONObject(it) }.map { o ->
            Entry(o.getString("id"), o.getJSONArray("to").let { a -> (0 until a.length()).map(a::getString) }, o.getJSONObject("body"),
                o.optInt("pow"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun write(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray(entries.map { JSONObject().put("id", it.id).put("to", JSONArray(it.to)).put("body", it.body).put("pow", it.pow).put("at", it.at) }).toString())
        tmp.renameTo(file)
    }

    companion object { const val KEEP_FOR = 30L * 24 * 3600 }
}
