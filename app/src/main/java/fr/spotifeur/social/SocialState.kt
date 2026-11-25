package fr.spotifeur.social

import org.json.JSONArray
import org.json.JSONObject

data class Friend(val pubKey: String, val name: String, val since: Long)
data class FriendRequest(val pubKey: String, val name: String, val note: String, val at: Long)

/** Where a share stands, for the one who received it and in the sender's outbox. Ordered: a state never goes back. */
enum class InboxState { NEW, SEEN, SAVED, DECLINED }

data class InboxItem(val id: String, val from: String, val item: JSONObject, val note: String, val at: Long, val state: InboxState) {
    // JSONObject has no value equality; compare by content.
    override fun equals(other: Any?) = other is InboxItem && id == other.id && from == other.from && note == other.note &&
        at == other.at && state == other.state && item.toString() == other.item.toString()
    override fun hashCode() = id.hashCode()
}

/** Where a friend's encrypted photo is and how to open it. */
data class PhotoRef(val urls: List<String>, val keyHex: String, val sha256: String) {
    fun toJson(): JSONObject = JSONObject().put("urls", JSONArray(urls)).put("key", keyHex).put("sha256", sha256)
    companion object {
        fun from(json: JSONObject?): PhotoRef? {
            json ?: return null
            val urls = json.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.startsWith("https://") }.take(6)
            val key = json.optString("key").takeIf { it.length == 64 } ?: return null
            val sha = json.optString("sha256").takeIf { it.length == 64 } ?: return null
            return if (urls.isEmpty()) null else PhotoRef(urls, key, sha)
        }
    }
}

/** What a friend is playing, until [until] (seconds); never stored. */
data class Listening(val id: String, val title: String, val subtitle: String, val image: String?, val at: Long, val until: Long)

data class FriendProfile(val bio: String, val photo: PhotoRef?, val at: Long)

data class OutboxItem(val id: String, val to: List<String>, val item: JSONObject, val at: Long, val states: Map<String, InboxState>, val lastSent: Long)

/**
 * The Friends state and its rules, without any network: who is a friend, pending requests, blocks, the inbox and what
 * we sent. Messages from [Messenger] go through [handle]; anything not allowed by these rules is dropped.
 */
class SocialState(private val me: String) {
    var friends: List<Friend> = emptyList(); private set
    var requests: List<FriendRequest> = emptyList(); private set
    /** Requests we sent and are waiting on, by public key. */
    var pending: Map<String, String> = emptyMap(); private set
    var blocked: Set<String> = emptySet(); private set
    var inbox: List<InboxItem> = emptyList(); private set
    var outbox: List<OutboxItem> = emptyList(); private set
    /** What friends told us about themselves (bio, photo), by public key. */
    var profiles: Map<String, FriendProfile> = emptyMap(); private set
    private var backupAt = 0L
    // Latest presence per friend, including "stopped" (null), so an older update cannot come back.
    private var listening: Map<String, Pair<Long, Listening?>> = emptyMap()

    fun listeningAt(now: Long): Map<String, Listening> =
        listening.mapNotNull { (key, value) -> value.second?.takeIf { it.until > now && isFriend(key) }?.let { key to it } }.toMap()

    fun isFriend(pubKey: String) = friends.any { it.pubKey == pubKey }

    fun handle(message: Messenger.Incoming): Boolean {
        val body = message.body
        val from = message.from
        if (from in blocked) return false
        return when (body.optString("type")) {
            "friend-request" -> {
                if (message.pow < REQUEST_POW || isFriend(from) || from == me) return false
                if (requests.any { it.pubKey == from }) return false
                requests = listOf(FriendRequest(from, body.optString("name").take(40), body.optString("note").take(140), message.sentAt)) + requests
                true
            }
            "friend-accept" -> {
                val name = pending[from] ?: return false
                pending = pending - from
                addFriend(from, body.optString("name").ifBlank { name }, message.sentAt)
                true
            }
            "unfriend" -> { friends = friends.filterNot { it.pubKey == from }; profiles = profiles - from; true }
            "share" -> {
                // Shares only go to friends: one from someone we asked means their acceptance was lost on the way.
                pending[from]?.let { name -> pending = pending - from; addFriend(from, name, message.sentAt) }
                if (!isFriend(from)) return false
                val id = body.optString("id").takeIf(String::isNotBlank) ?: return false
                if (inbox.any { it.id == id }) return false
                val item = body.optJSONObject("item") ?: return false
                inbox = listOf(InboxItem(id, from, item, body.optString("note").take(140), message.sentAt, InboxState.NEW)) + inbox
                true
            }
            "receipt" -> {
                val of = body.optString("of")
                val state = runCatching { InboxState.valueOf(body.optString("state").uppercase()) }.getOrNull() ?: return false
                var changed = false
                outbox = outbox.map { sent ->
                    if (sent.id != of || from !in sent.to) return@map sent
                    val now = sent.states[from] ?: InboxState.NEW
                    if (state.ordinal <= now.ordinal) return@map sent
                    changed = true
                    sent.copy(states = sent.states + (from to state))
                }
                changed
            }
            "profile" -> {
                if (!isFriend(from)) return false
                val at = body.optLong("at")
                if (at <= (profiles[from]?.at ?: 0)) return false
                profiles = profiles + (from to FriendProfile(body.optString("bio").take(160), PhotoRef.from(body.optJSONObject("photo")), at))
                body.optString("name").trim().take(40).takeIf(String::isNotEmpty)?.let { name ->
                    friends = friends.map { if (it.pubKey == from) it.copy(name = name) else it }
                }
                true
            }
            "listening" -> {
                if (!isFriend(from)) return false
                val at = body.optLong("at")
                if (at <= (listening[from]?.first ?: 0)) return false
                val track = body.optJSONObject("track")
                val id = track?.optString("id").orEmpty()
                val now = if (track != null && (id.startsWith("spotify:track:") || id.startsWith("ytm:track:"))) Listening(id,
                    track.optString("title").take(200), track.optString("subtitle").take(200),
                    track.optString("image").takeIf { it.startsWith("https://") }, at, minOf(body.optLong("until"), at + 3600)) else null
                listening = listening + (from to (at to now))
                true
            }
            "self-backup" -> {
                if (from != me) return false
                val at = body.optLong("at")
                if (at <= backupAt) return false
                backupAt = at
                val known = friends.map { it.pubKey }.toSet()
                friends = friends + friendsOf(body.optJSONArray("friends")).filterNot { it.pubKey in known }
                blocked = blocked + strings(body.optJSONArray("blocked"))
                true
            }
            else -> false
        }
    }

    fun requested(pubKey: String, name: String) { pending = pending + (pubKey to name) }

    fun accept(pubKey: String) {
        val request = requests.firstOrNull { it.pubKey == pubKey } ?: return
        requests = requests - request
        addFriend(pubKey, request.name, System.currentTimeMillis() / 1000)
    }

    fun decline(pubKey: String) { requests = requests.filterNot { it.pubKey == pubKey } }

    fun remove(pubKey: String) { friends = friends.filterNot { it.pubKey == pubKey }; profiles = profiles - pubKey }

    fun block(pubKey: String) {
        blocked = blocked + pubKey
        friends = friends.filterNot { it.pubKey == pubKey }
        profiles = profiles - pubKey
        requests = requests.filterNot { it.pubKey == pubKey }
        pending = pending - pubKey
    }

    fun unblock(pubKey: String) { blocked = blocked - pubKey }

    fun sent(id: String, to: List<String>, item: JSONObject, at: Long) {
        outbox = listOf(OutboxItem(id, to, item, at, to.associateWith { InboxState.NEW }, at)) + outbox
    }

    fun markSent(id: String, at: Long) { outbox = outbox.map { if (it.id == id) it.copy(lastSent = at) else it } }

    fun setInbox(id: String, state: InboxState) {
        inbox = inbox.map { if (it.id == id && state.ordinal > it.state.ordinal) it.copy(state = state) else it }
    }

    private fun addFriend(pubKey: String, name: String, at: Long) {
        if (isFriend(pubKey)) return
        friends = friends + Friend(pubKey, name.take(40), at)
        requests = requests.filterNot { it.pubKey == pubKey }
    }

    /** What we send to ourselves so a restored identity gets its friends and blocks back. */
    fun backup(): JSONObject = JSONObject().put("type", "self-backup").put("at", System.currentTimeMillis() / 1000)
        .put("friends", friendsJson()).put("blocked", JSONArray(blocked.toList()))

    fun toJson(): JSONObject = JSONObject()
        .put("friends", friendsJson())
        .put("requests", JSONArray(requests.map { JSONObject().put("pubKey", it.pubKey).put("name", it.name).put("note", it.note).put("at", it.at) }))
        .put("pending", JSONObject(pending))
        .put("blocked", JSONArray(blocked.toList()))
        .put("inbox", JSONArray(inbox.map { JSONObject().put("id", it.id).put("from", it.from).put("item", it.item).put("note", it.note).put("at", it.at).put("state", it.state.name) }))
        .put("outbox", JSONArray(outbox.map { o -> JSONObject().put("id", o.id).put("to", JSONArray(o.to)).put("item", o.item).put("at", o.at)
            .put("lastSent", o.lastSent).put("states", JSONObject(o.states.mapValues { it.value.name })) }))
        .put("backupAt", backupAt)
        .put("profiles", JSONObject(profiles.mapValues { (_, p) -> JSONObject().put("bio", p.bio).put("at", p.at).apply { p.photo?.let { put("photo", it.toJson()) } } }))

    private fun friendsJson() = JSONArray(friends.map { JSONObject().put("pubKey", it.pubKey).put("name", it.name).put("since", it.since) })

    companion object {
        /** Proof of work a friend request must carry (NIP-13 bits): under a second on a phone, costly in bulk. */
        const val REQUEST_POW = 16

        fun fromJson(me: String, json: JSONObject): SocialState = SocialState(me).apply {
            friends = friendsOf(json.optJSONArray("friends"))
            requests = objects(json.optJSONArray("requests")).map { FriendRequest(it.getString("pubKey"), it.optString("name"), it.optString("note"), it.optLong("at")) }
            pending = json.optJSONObject("pending")?.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }.orEmpty()
            blocked = strings(json.optJSONArray("blocked")).toSet()
            inbox = objects(json.optJSONArray("inbox")).map {
                InboxItem(it.getString("id"), it.getString("from"), it.getJSONObject("item"), it.optString("note"), it.optLong("at"),
                    runCatching { InboxState.valueOf(it.optString("state")) }.getOrDefault(InboxState.NEW))
            }
            outbox = objects(json.optJSONArray("outbox")).map { o ->
                val states = o.optJSONObject("states")?.let { s -> s.keys().asSequence().associateWith { k -> runCatching { InboxState.valueOf(s.getString(k)) }.getOrDefault(InboxState.NEW) } }.orEmpty()
                OutboxItem(o.getString("id"), strings(o.optJSONArray("to")), o.getJSONObject("item"), o.optLong("at"), states, o.optLong("lastSent"))
            }
            backupAt = json.optLong("backupAt")
            profiles = json.optJSONObject("profiles")?.let { all ->
                all.keys().asSequence().associateWith { k -> all.getJSONObject(k).let { FriendProfile(it.optString("bio"), PhotoRef.from(it.optJSONObject("photo")), it.optLong("at")) } }
            }.orEmpty()
        }

        private fun objects(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array!!.optJSONObject(it) }
        private fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }
        private fun friendsOf(array: JSONArray?) = objects(array).map { Friend(it.getString("pubKey"), it.optString("name"), it.optLong("since")) }
    }
}
