package fr.spotifeur.social

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class BlendView(val id: String, val friend: String, val tracks: List<JSONObject>, val localId: String?, val waiting: Boolean)
data class BlendInvite(val id: String, val from: String, val at: Long)

/**
 * Blends: made only when both friends accept. Each side sends the other its taste (encrypted, refreshed daily); each
 * phone builds the same 50 songs with [BlendBuilder]. Stopping keeps the songs as a plain playlist on both phones.
 */
object Blends {
    /** Up to 200 liked and most played songs, each with "plays"; provided by the app. */
    var taste: (suspend () -> List<JSONObject>)? = null

    /** [mine] is exactly the taste we sent: both phones build from the same two lists, never from a newer local one. */
    private class Record(val id: String, val friend: String, var active: Boolean, var theirs: List<JSONObject>, var theirDate: String,
                         var mySentDate: String, var localId: String?, var mine: List<JSONObject> = emptyList())

    private val lock = Mutex()
    private val records = LinkedHashMap<String, Record>()
    private val invites = LinkedHashMap<String, BlendInvite>()
    private var loaded = false
    private val mutableViews = MutableStateFlow<List<BlendView>>(emptyList())
    val views: StateFlow<List<BlendView>> = mutableViews.asStateFlow()
    private val mutableInvites = MutableStateFlow<List<BlendInvite>>(emptyList())
    val pendingInvites: StateFlow<List<BlendInvite>> = mutableInvites.asStateFlow()

    private fun file(context: Context) = File(File(context.filesDir, "social").apply { mkdirs() }, "blends.json")
    // UTC, so two friends in different time zones build the same Blend on the same day.
    private fun today() = LocalDate.now(java.time.ZoneOffset.UTC).toString()

    private suspend fun load(context: Context) = lock.withLock {
        if (loaded) return@withLock
        loaded = true
        val json = runCatching { JSONObject(file(context).readText()) }.getOrNull() ?: return@withLock
        json.optJSONArray("records")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { r ->
            records[r.getString("id")] = Record(r.getString("id"), r.getString("friend"), r.optBoolean("active"),
                r.optJSONArray("theirs")?.let { t -> (0 until t.length()).map(t::getJSONObject) }.orEmpty(), r.optString("theirDate"),
                r.optString("mySentDate"), r.optString("localId").ifEmpty { null },
                r.optJSONArray("mine")?.let { t -> (0 until t.length()).map(t::getJSONObject) }.orEmpty())
        } }
        json.optJSONArray("invites")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { v ->
            invites[v.getString("id")] = BlendInvite(v.getString("id"), v.getString("from"), v.optLong("at"))
        } }
        publish()
    }

    private fun save(context: Context) {
        val json = JSONObject().put("records", JSONArray(records.values.map { r ->
            JSONObject().put("id", r.id).put("friend", r.friend).put("active", r.active).put("theirs", JSONArray(r.theirs))
                .put("theirDate", r.theirDate).put("mySentDate", r.mySentDate).put("localId", r.localId ?: "").put("mine", JSONArray(r.mine))
        })).put("invites", JSONArray(invites.values.map { JSONObject().put("id", it.id).put("from", it.from).put("at", it.at) }))
        val tmp = File(file(context).path + ".tmp"); tmp.writeText(json.toString()); tmp.renameTo(file(context))
    }

    private fun publish() {
        val me = Social.snapshot.value.me
        mutableViews.value = records.values.filter { it.active }.map { r ->
            // Built once both tastes are in (either may be empty: a new user still gets the friend's songs).
            val ready = me != null && r.theirDate.isNotEmpty() && r.mySentDate.isNotEmpty()
            val built = if (ready) BlendBuilder.build(me!! to r.mine, r.friend to r.theirs, r.theirDate.coerceAtLeast(r.mySentDate)) else emptyList()
            BlendView(r.id, r.friend, built, r.localId, waiting = !ready)
        }
        mutableInvites.value = invites.values.toList()
    }

    /** Asks [friend] for a Blend; nothing is made until they accept. */
    suspend fun invite(context: Context, friend: String) {
        load(context)
        if (lock.withLock { records.values.any { it.friend == friend } }) return
        val id = java.util.UUID.randomUUID().toString()
        lock.withLock { records[id] = Record(id, friend, active = false, theirs = emptyList(), theirDate = "", mySentDate = "", localId = null); save(context) }
        Social.deliverTo(listOf(friend), JSONObject().put("type", "blend-invite").put("id", id))
    }

    suspend fun accept(context: Context, id: String) {
        load(context)
        val invite = lock.withLock { invites.remove(id)?.also { records[id] = Record(id, it.from, true, emptyList(), "", "", null); save(context); publish() } } ?: return
        Social.deliverTo(listOf(invite.from), JSONObject().put("type", "blend-accept").put("id", id))
        sendTaste(context, id)
    }

    suspend fun decline(context: Context, id: String) {
        load(context)
        val invite = lock.withLock { invites.remove(id)?.also { save(context); publish() } } ?: return
        Social.deliverTo(listOf(invite.from), JSONObject().put("type", "blend-stop").put("id", id))
    }

    suspend fun stop(context: Context, id: String) {
        load(context)
        val record = lock.withLock { records.remove(id)?.also { save(context); publish() } } ?: return
        Social.deliverTo(listOf(record.friend), JSONObject().put("type", "blend-stop").put("id", id))
    }

    suspend fun link(context: Context, id: String, localId: String) = lock.withLock { records[id]?.let { it.localId = localId; save(context); publish() } }

    /** Sends our taste to every active Blend that has not had today's (called at start and every few hours). */
    suspend fun refresh(context: Context) {
        load(context)
        val due = lock.withLock { records.values.filter { it.active && it.mySentDate != today() }.map { it.id } }
        due.forEach { sendTaste(context, it) }
        lock.withLock { publish() }
    }

    private suspend fun sendTaste(context: Context, id: String) {
        val mine = taste?.invoke().orEmpty().take(200)
        android.util.Log.i("SpotifeurSocial", "Blend taste: ${mine.size} songs")
        val date = today()
        val friend = lock.withLock { records[id]?.also { it.mine = mine; it.mySentDate = date; save(context) }?.friend } ?: return
        Social.deliverTo(listOf(friend), JSONObject().put("type", "blend-taste").put("id", id).put("date", date).put("tracks", JSONArray(mine)))
        lock.withLock { publish() }
    }

    internal suspend fun onDirect(context: Context, message: Messenger.Incoming, isFriend: Boolean) {
        load(context)
        val body = message.body
        val id = body.optString("id").takeIf { it.isNotBlank() } ?: return
        when (body.optString("type")) {
            "blend-invite" -> {
                if (!isFriend) return
                val isNew = lock.withLock {
                    if (records.containsKey(id) || invites.containsKey(id)) false
                    else { invites[id] = BlendInvite(id, message.from, message.sentAt); save(context); publish(); true }
                }
                if (isNew) SocialNotifier.blendInvite(context, Social.nameOf(message.from))
            }
            "blend-accept" -> {
                val ok = lock.withLock { records[id]?.takeIf { it.friend == message.from && !it.active }?.also { it.active = true; save(context); publish() } != null }
                if (ok) sendTaste(context, id)
            }
            "blend-taste" -> {
                val tracks = body.optJSONArray("tracks")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().take(200)
                val needMine = lock.withLock {
                    val r = records[id]?.takeIf { it.friend == message.from && it.active } ?: return
                    r.theirs = tracks; r.theirDate = body.optString("date"); save(context)
                    r.mySentDate != today()
                }
                if (needMine) sendTaste(context, id) else lock.withLock { publish() }
            }
            "blend-stop" -> lock.withLock {
                if (records[id]?.friend == message.from) { records.remove(id); save(context); publish() }
                if (invites[id]?.from == message.from) { invites.remove(id); save(context); publish() }
            }
        }
    }

    internal suspend fun start(context: Context) { load(context); refresh(context) }
}
