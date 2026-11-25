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

/** A shared playlist as the screens and the library see it. */
data class SharedView(
    val id: String, val title: String, val owner: String, val members: List<String>, val entries: List<PlaylistEntry>,
    /** The local playlist that holds it on this phone; null until the app made one. */
    val localId: String?,
)

/** An invitation to edit a playlist together, waiting for Join or Decline. */
data class PlaylistInvite(val id: String, val from: String, val title: String, val songs: Int, val members: List<String>, val at: Long)

/**
 * Shared playlists: invitations (gift-wrapped, need a Join), membership (only the creator changes it; removing someone
 * makes a new key), and edits (operations encrypted with the group key, batched, kept on the phone until sent).
 */
object SharedPlaylists {
    private class Record(var group: SharedGroup, var older: List<SharedGroup>, val doc: SharedPlaylistDoc, var localId: String?,
                         val unsent: MutableList<JSONObject>, var opsSinceSnapshot: Int)

    private val lock = Mutex()
    private val records = LinkedHashMap<String, Record>()
    private val invites = LinkedHashMap<String, Pair<PlaylistInvite, JSONObject>>() // invite, its raw body
    private var loaded = false
    private val mutableViews = MutableStateFlow<List<SharedView>>(emptyList())
    val views: StateFlow<List<SharedView>> = mutableViews.asStateFlow()
    private val mutableInvites = MutableStateFlow<List<PlaylistInvite>>(emptyList())
    val pendingInvites: StateFlow<List<PlaylistInvite>> = mutableInvites.asStateFlow()

    private fun file(context: Context) = File(File(context.filesDir, "social").apply { mkdirs() }, "shared.json")

    internal suspend fun load(context: Context) = lock.withLock {
        if (loaded) return@withLock
        loaded = true
        val json = runCatching { JSONObject(file(context).readText()) }.getOrNull() ?: return@withLock
        json.optJSONArray("records")?.let { array ->
            for (i in 0 until array.length()) {
                val r = array.getJSONObject(i)
                val group = SharedGroup.fromJson(r.getJSONObject("group")) ?: continue
                val older = r.optJSONArray("older")?.let { a -> (0 until a.length()).mapNotNull { SharedGroup.fromJson(a.getJSONObject(it)) } }.orEmpty()
                val doc = SharedPlaylistDoc.fromSnapshot(r.getJSONObject("doc")).also { it.setMembers(group.members.toSet()) }
                val unsent = r.optJSONArray("unsent")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().toMutableList()
                records[group.id] = Record(group, older, doc, r.optString("localId").ifEmpty { null }, unsent, r.optInt("since"))
            }
        }
        json.optJSONArray("invites")?.let { array ->
            for (i in 0 until array.length()) inviteFrom(array.getJSONObject(i).getString("from"), array.getJSONObject(i).getJSONObject("body"),
                array.getJSONObject(i).optLong("at"))?.let { invites[it.first.id] = it }
        }
        publishViews()
    }

    private fun save(context: Context) {
        val json = JSONObject()
            .put("records", JSONArray(records.values.map { r ->
                JSONObject().put("group", r.group.toJson()).put("older", JSONArray(r.older.map { it.toJson() })).put("doc", r.doc.snapshot())
                    .put("localId", r.localId ?: "").put("unsent", JSONArray(r.unsent)).put("since", r.opsSinceSnapshot)
            }))
            .put("invites", JSONArray(invites.values.map { (invite, body) -> JSONObject().put("from", invite.from).put("body", body).put("at", invite.at) }))
        val target = file(context)
        val tmp = File(target.path + ".tmp"); tmp.writeText(json.toString()); tmp.renameTo(target)
    }

    private fun publishViews() {
        mutableViews.value = records.values.map { r -> SharedView(r.group.id, r.doc.title.ifBlank { r.group.title }, r.group.owner, r.group.members, r.doc.items(), r.localId) }
        mutableInvites.value = invites.values.map { it.first }
    }

    /** Every key epoch still read: the current one and the last few, for edits made just before a change. */
    internal fun watchedGroups(): List<SharedGroup> = records.values.flatMap { listOf(it.group) + it.older }

    private fun rewatch() = Social.rewatchGroups()

    // Creating and inviting -------------------------------------------------------------------------------------

    /**
     * Makes [localId] (a playlist of this phone) a shared playlist with [friends] invited; they become members when
     * they accept.
     */
    suspend fun create(context: Context, localId: String, title: String, tracks: List<JSONObject>, friends: List<String>) {
        val me = Social.snapshot.value.me ?: return
        load(context)
        val group = SharedGroup.create(java.util.UUID.randomUUID().toString(), title, me, listOf(me) + friends)
        val doc = SharedPlaylistDoc(group.id, group.members.toSet())
        doc.rename(me, title)
        doc.edit(me, tracks, null)
        lock.withLock {
            records[group.id] = Record(group, emptyList(), doc, localId, mutableListOf(), 0)
            save(context); publishViews()
        }
        rewatch()
        friends.forEach { invite(group, doc, it) }
    }

    /** The creator adds a friend to a playlist already shared. */
    suspend fun addMember(context: Context, id: String, friend: String) {
        val (group, doc) = lock.withLock {
            val r = records[id] ?: return
            if (r.group.owner != Social.snapshot.value.me || friend in r.group.members) return
            r.group = r.group.with(friend); r.doc.setMembers(r.group.members.toSet()); save(context); publishViews()
            r.group to r.doc
        }
        invite(group, doc, friend)
        sendMembers(group)
    }

    private suspend fun invite(group: SharedGroup, doc: SharedPlaylistDoc, to: String) {
        Social.deliverTo(listOf(to), JSONObject().put("type", "playlist-invite").put("group", group.toJson()).put("snapshot", doc.snapshot()))
    }

    private fun inviteFrom(from: String, body: JSONObject, at: Long): Pair<PlaylistInvite, JSONObject>? {
        val group = SharedGroup.fromJson(body.optJSONObject("group") ?: return null) ?: return null
        if (group.owner != from) return null
        val doc = runCatching { SharedPlaylistDoc.fromSnapshot(body.getJSONObject("snapshot")) }.getOrNull() ?: return null
        if (doc.id != group.id) return null
        return PlaylistInvite(group.id, from, doc.title.ifBlank { group.title }, doc.items().size, group.members, at) to body
    }

    suspend fun join(context: Context, id: String) {
        val body = lock.withLock { invites.remove(id)?.second } ?: return
        val group = SharedGroup.fromJson(body.getJSONObject("group")) ?: return
        val doc = SharedPlaylistDoc.fromSnapshot(body.getJSONObject("snapshot")).also { it.setMembers(group.members.toSet()) }
        lock.withLock { records[group.id] = Record(group, emptyList(), doc, null, mutableListOf(), 0); save(context); publishViews() }
        rewatch()
        Social.deliverTo(listOf(group.owner), JSONObject().put("type", "playlist-join").put("id", id))
    }

    suspend fun decline(context: Context, id: String) {
        val invite = lock.withLock { invites.remove(id)?.first.also { save(context); publishViews() } } ?: return
        Social.deliverTo(listOf(invite.from), JSONObject().put("type", "playlist-decline").put("id", id))
    }

    /** Leaving keeps the songs here as a plain playlist; the creator then shuts us out with a new key. */
    suspend fun leave(context: Context, id: String) {
        val record = lock.withLock { records.remove(id)?.also { save(context); publishViews() } } ?: return
        rewatch()
        val me = Social.snapshot.value.me
        if (record.group.owner == me) {
            // The creator leaving ends the sharing for everyone.
            sendMembers(record.group.copy(members = listOf(record.group.owner)).without(""), to = record.group.members.filter { it != me })
        } else Social.deliverTo(listOf(record.group.owner), JSONObject().put("type", "playlist-leave").put("id", id))
    }

    suspend fun removeMember(context: Context, id: String, member: String) {
        val group = lock.withLock {
            val r = records[id] ?: return
            if (r.group.owner != Social.snapshot.value.me || member == r.group.owner) return
            r.older = (listOf(r.group) + r.older).take(3)
            r.group = r.group.without(member)
            r.doc.setMembers(r.group.members.toSet())
            save(context); publishViews()
            r.group
        }
        rewatch()
        sendMembers(group)
    }

    private suspend fun sendMembers(group: SharedGroup, to: List<String>? = null) {
        val me = Social.snapshot.value.me
        val targets = to ?: group.members.filter { it != me }
        if (targets.isNotEmpty()) Social.deliverTo(targets, JSONObject().put("type", "playlist-members").put("group", group.toJson()))
    }

    /** The app made (or found) the local playlist that holds [id]. */
    suspend fun link(context: Context, id: String, localId: String) = lock.withLock {
        records[id]?.let { it.localId = localId; save(context); publishViews() }
    }

    // Direct messages (gift wraps) ------------------------------------------------------------------------------

    internal suspend fun onDirect(context: Context, message: Messenger.Incoming, isFriend: Boolean) {
        load(context)
        val body = message.body
        val me = Social.snapshot.value.me ?: return
        when (body.optString("type")) {
            "playlist-invite" -> {
                if (!isFriend) return
                val invite = inviteFrom(message.from, body, message.sentAt) ?: return
                if (me !in invite.first.members) return
                val isNew = lock.withLock {
                    if (records.containsKey(invite.first.id) || invites.containsKey(invite.first.id)) false
                    else { invites[invite.first.id] = invite; save(context); publishViews(); true }
                }
                if (isNew) SocialNotifier.playlistInvite(context, Social.nameOf(message.from), invite.first.title)
            }
            "playlist-join" -> Unit // already a member since the invitation
            "playlist-decline", "playlist-leave" -> {
                val id = body.optString("id")
                val owned = lock.withLock { records[id]?.takeIf { it.group.owner == me && message.from in it.group.members } } ?: return
                removeMember(context, owned.group.id, message.from)
            }
            "playlist-members" -> {
                val next = SharedGroup.fromJson(body.optJSONObject("group") ?: return) ?: return
                val removed = lock.withLock {
                    val r = records[next.id] ?: return
                    if (message.from != r.group.owner || next.owner != r.group.owner) return
                    if (next.epoch < r.group.epoch) return
                    if (next.epoch > r.group.epoch) r.older = (listOf(r.group) + r.older).take(3)
                    r.group = next
                    r.doc.setMembers(next.members.toSet())
                    val out = me !in next.members
                    if (out) records.remove(next.id)
                    save(context); publishViews()
                    out
                }
                rewatch()
                if (removed) android.util.Log.i("SpotifeurSocial", "no longer a member of a shared playlist; its songs stay here")
            }
        }
    }

    // Group messages (edits) ------------------------------------------------------------------------------------

    private var batchJob: kotlinx.coroutines.Job? = null
    private val addedBy = HashMap<String, MutableMap<String, Int>>() // playlist -> author -> songs added, for one notification

    internal suspend fun onGroup(context: Context, message: Messenger.GroupIncoming) {
        if (message.live) { Jam.onGroup(message); return }
        load(context)
        val me = Social.snapshot.value.me
        val body = message.body
        lock.withLock {
            val r = records[message.group.id] ?: return
            var changed = false
            when (body.optString("type")) {
                "ops" -> body.optJSONArray("ops")?.let { array ->
                    for (i in 0 until array.length()) {
                        val op = GroupOp.fromJson(array.getJSONObject(i)) ?: continue
                        if (op.author != message.author) continue // nobody edits in someone else's name
                        if (r.doc.apply(op)) {
                            changed = true; r.opsSinceSnapshot++
                            if (op.type == "add" && op.author != me) addedBy.getOrPut(r.group.id) { HashMap() }.merge(op.author, 1, Int::plus)
                        }
                    }
                }
                "snapshot" -> body.optJSONObject("doc")?.let { snapshot ->
                    runCatching { SharedPlaylistDoc.fromSnapshot(snapshot) }.getOrNull()?.takeIf { it.id == r.doc.id }?.let { r.doc.merge(it); changed = true }
                }
            }
            if (!changed) return
            save(context); publishViews()
        }
        scheduleEditNotice(context)
        maybeSnapshot(message.group.id)
    }

    /** "Sara added 3 songs to Road trip": one notification for a burst of edits, never one per song. */
    private fun scheduleEditNotice(context: Context) {
        batchJob?.cancel()
        batchJob = Social.launchJob {
            kotlinx.coroutines.delay(15_000)
            val pending = lock.withLock { HashMap(addedBy).also { addedBy.clear() } }
            pending.forEach { (playlist, authors) ->
                val title = views.value.firstOrNull { it.id == playlist }?.title ?: return@forEach
                authors.forEach { (author, count) -> SocialNotifier.playlistEdits(context, Social.nameOf(author), count, title) }
            }
        }
    }

    // Local edits ----------------------------------------------------------------------------------------------

    /** The local playlist [id] now holds [tracks] and is called [title]: the difference goes to the other members. */
    suspend fun edited(context: Context, id: String, tracks: List<JSONObject>, title: String) {
        val me = Social.snapshot.value.me ?: return
        load(context)
        val (group, ops) = lock.withLock {
            val r = records[id] ?: return
            val ops = r.doc.edit(me, tracks, title)
            if (ops.isEmpty()) return
            r.unsent += ops.map { it.toJson() }
            r.opsSinceSnapshot += ops.size
            save(context); publishViews()
            r.group to ops
        }
        flush(context, group.id)
        maybeSnapshot(group.id)
    }

    /** Sends the edits not yet sent (also called by the retry loop). */
    internal suspend fun flush(context: Context, id: String? = null) {
        val m = Social.messengerOrNull() ?: return
        val targets = lock.withLock { records.values.filter { (id == null || it.group.id == id) && it.unsent.isNotEmpty() }.map { it.group to it.unsent.toList() } }
        for ((group, ops) in targets) {
            for (batch in ops.chunked(200)) {
                if (!m.publishGroup(group, JSONObject().put("type", "ops").put("ops", JSONArray(batch)))) return
                lock.withLock { records[group.id]?.unsent?.removeAll(batch.toSet()); save(context) }
            }
        }
    }

    /** After 50 edits the creator posts the whole state, so relays dropping old events loses nothing. */
    private suspend fun maybeSnapshot(id: String) {
        val m = Social.messengerOrNull() ?: return
        val me = Social.snapshot.value.me
        val (group, snapshot) = lock.withLock {
            val r = records[id] ?: return
            if (r.group.owner != me || r.opsSinceSnapshot < 50) return
            r.opsSinceSnapshot = 0
            r.group to r.doc.snapshot()
        }
        m.publishGroup(group, JSONObject().put("type", "snapshot").put("doc", snapshot))
    }

    internal suspend fun startWatching(context: Context) { load(context); rewatch(); flush(context) }
}
