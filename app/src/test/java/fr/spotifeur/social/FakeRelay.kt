package fr.spotifeur.social

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * A NIP-01 relay in memory, for tests: stores events (ephemeral kinds only forwarded), answers REQ with stored matches
 * then EOSE, forwards new events to live subscriptions, answers EVENT with OK. Filters: ids, authors, kinds, since,
 * until and single-letter tag filters.
 */
class FakeRelay : WebsocketBuilder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stored = mutableListOf<JSONObject>()
    private val connections = mutableListOf<Conn>()
    val published: List<JSONObject> get() = synchronized(this) { stored.toList() }

    override fun build(url: NormalizedRelayUrl, out: WebSocketListener): WebSocket = Conn(out).also { synchronized(this) { connections += it } }

    fun close() = scope.cancel()

    inner class Conn(private val out: WebSocketListener) : WebSocket {
        @Volatile private var open = false
        private val outbox = Channel<String>(Channel.UNLIMITED)
        val subs = HashMap<String, List<JSONObject>>()

        init { scope.launch { for (m in outbox) out.onMessage(m) } }

        override fun needsReconnect() = !open
        override fun connect() { open = true; out.onOpen(0, false) }
        override fun disconnect() { open = false; out.onClosed(1000, "bye") }

        override fun send(msg: String): Boolean {
            val a = JSONArray(msg)
            when (a.getString(0)) {
                "EVENT" -> {
                    val e = a.getJSONObject(1)
                    val kind = e.getInt("kind")
                    synchronized(this@FakeRelay) {
                        // NIP-01 replaceable (10000-19999) and addressable (30000-39999) events keep only the newest.
                        fun d(x: JSONObject) = x.getJSONArray("tags").let { t -> (0 until t.length()).map { t.getJSONArray(it) }.firstOrNull { it.length() > 1 && it.getString(0) == "d" }?.getString(1).orEmpty() }
                        if (kind in 10000..19999 || kind in 30000..39999) stored.removeAll { it.getInt("kind") == kind && it.getString("pubkey") == e.getString("pubkey") && (kind < 30000 || d(it) == d(e)) }
                        if (kind !in 20000..29999) stored += e
                    }
                    outbox.trySend(JSONArray(listOf("OK", e.getString("id"), true, "")).toString())
                    val live = synchronized(this@FakeRelay) { connections.toList() }
                    live.forEach { c -> synchronized(c.subs) { c.subs.toMap() }.forEach { (id, filters) -> if (filters.any { matches(it, e) }) c.outbox.trySend(JSONArray(listOf("EVENT", id, e)).toString()) } }
                }
                "REQ" -> {
                    val id = a.getString(1)
                    val filters = (2 until a.length()).map { a.getJSONObject(it) }
                    synchronized(subs) { subs[id] = filters }
                    synchronized(this@FakeRelay) { stored.toList() }.filter { e -> filters.any { matches(it, e) } }
                        .forEach { outbox.trySend(JSONArray(listOf("EVENT", id, it)).toString()) }
                    outbox.trySend(JSONArray(listOf("EOSE", id)).toString())
                }
                "CLOSE" -> synchronized(subs) { subs.remove(a.getString(1)) }
            }
            return true
        }
    }

    companion object {
        fun matches(f: JSONObject, e: JSONObject): Boolean {
            fun list(key: String) = f.optJSONArray(key)?.let { arr -> (0 until arr.length()).map { arr.get(it).toString() } }
            list("ids")?.let { if (e.getString("id") !in it) return false }
            list("authors")?.let { if (e.getString("pubkey") !in it) return false }
            list("kinds")?.let { if (e.getInt("kind").toString() !in it) return false }
            if (f.has("since") && e.getLong("created_at") < f.getLong("since")) return false
            if (f.has("until") && e.getLong("created_at") > f.getLong("until")) return false
            val tags = e.getJSONArray("tags")
            f.keys().asSequence().filter { it.startsWith("#") && it.length == 2 }.forEach { key ->
                val wanted = list(key)!!
                val name = key.substring(1)
                val has = (0 until tags.length()).any { i -> val t = tags.getJSONArray(i); t.length() > 1 && t.getString(0) == name && t.getString(1) in wanted }
                if (!has) return false
            }
            return true
        }
    }
}
