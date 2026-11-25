package fr.spotifeur.social

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Fractional position keys: strings over an ordered alphabet where a key can always be made between two others, so
 * an insert or a move changes one item only. A key never ends with the lowest digit, which keeps room below it.
 */
object Positions {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /** A key strictly between [a] and [b]; null means the start (for [a]) or the end (for [b]). */
    fun between(a: String?, b: String?): String {
        val low = a.orEmpty()
        if (b != null && low >= b) return low + DIGITS[DIGITS.length / 2] // equal neighbours (a concurrent tie): just after
        return midpoint(low, b)
    }

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            var n = 0
            while (n < b.length && (a.getOrNull(n) ?: '0') == b[n]) n++
            if (n > 0) return b.take(n) + midpoint(a.drop(n), b.drop(n))
        }
        val da = a.firstOrNull()?.let(DIGITS::indexOf) ?: 0
        val db = b?.firstOrNull()?.let(DIGITS::indexOf) ?: DIGITS.length
        if (db - da > 1) return DIGITS[(da + db) / 2].toString()
        // Adjacent digits: keep a's digit and go one level deeper.
        if (b != null && b.length > 1) return b.take(1)
        return DIGITS[da] + midpoint(a.drop(1), null)
    }
}

/** One edit of a shared playlist. An add's id is also the new item's id. */
data class GroupOp(
    val id: String, val playlist: String, val author: String, val clock: Long, val type: String,
    val item: String? = null, val pos: String? = null, val track: JSONObject? = null, val title: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("playlist", playlist).put("author", author).put("clock", clock).put("type", type).apply {
        item?.let { put("item", it) }; pos?.let { put("pos", it) }; track?.let { put("track", it) }; title?.let { put("title", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): GroupOp? = runCatching {
            GroupOp(json.getString("id"), json.getString("playlist"), json.getString("author"), json.getLong("clock"), json.getString("type"),
                json.optString("item").ifEmpty { null }, json.optString("pos").ifEmpty { null }, json.optJSONObject("track"),
                json.optString("title").ifEmpty { null })
        }.getOrNull()
    }
}

data class PlaylistEntry(val item: String, val track: JSONObject, val addedBy: String, val pos: String)

/**
 * A shared playlist as every member's phone holds it. Applying the same operations in any order, with repeats, gives
 * the same list: items are ordered by position key then id; an item's position and the title keep the latest write
 * by (logical clock, author); a remove is final, even against a later move.
 */
class SharedPlaylistDoc(val id: String, members: Set<String>) {
    private class Slot(var track: JSONObject? = null, var addedBy: String? = null, var pos: String? = null,
                       var stampClock: Long = -1, var stampAuthor: String = "", var removed: Boolean = false)

    private var members: Set<String> = members
    private val seen = LinkedHashSet<String>()
    private val slots = HashMap<String, Slot>()
    var title: String = ""; private set
    private var titleClock = -1L
    private var titleAuthor = ""
    var clock = 0L; private set

    fun setMembers(next: Set<String>) { members = next }

    /** False for a repeat, another playlist's op or an op from someone who is not a member. */
    fun apply(op: GroupOp): Boolean {
        if (op.playlist != id || op.author !in members || op.id in seen) return false
        seen += op.id
        clock = maxOf(clock, op.clock)
        when (op.type) {
            "add" -> {
                val slot = slots.getOrPut(op.item ?: op.id) { Slot() }
                if (slot.track == null) { slot.track = op.track; slot.addedBy = op.author }
                place(slot, op.pos, op.clock, op.author)
            }
            "move" -> place(slots.getOrPut(op.item ?: return true) { Slot() }, op.pos, op.clock, op.author)
            "remove" -> slots.getOrPut(op.item ?: return true) { Slot() }.removed = true
            "rename" -> if (later(op.clock, op.author, titleClock, titleAuthor)) { title = op.title.orEmpty().take(100); titleClock = op.clock; titleAuthor = op.author }
        }
        return true
    }

    private fun place(slot: Slot, pos: String?, clock: Long, author: String) {
        if (pos == null || !later(clock, author, slot.stampClock, slot.stampAuthor)) return
        slot.pos = pos; slot.stampClock = clock; slot.stampAuthor = author
    }

    private fun later(clock: Long, author: String, thanClock: Long, thanAuthor: String) =
        clock > thanClock || clock == thanClock && author > thanAuthor

    fun items(): List<PlaylistEntry> = slots.mapNotNull { (item, s) ->
        if (s.removed) null else PlaylistEntry(item, s.track ?: return@mapNotNull null, s.addedBy ?: return@mapNotNull null, s.pos ?: return@mapNotNull null)
    }.sortedWith(compareBy({ it.pos }, { it.item }))

    // Local edits: made, applied here, and returned to be sent to the other members.

    fun add(author: String, track: JSONObject, after: String?): GroupOp = newId().let { item ->
        local(GroupOp(item, id, author, clock + 1, "add", item = item, pos = positionAfter(after, moving = null), track = track))
    }

    fun move(author: String, item: String, after: String?): GroupOp =
        local(GroupOp(newId(), id, author, clock + 1, "move", item = item, pos = positionAfter(after, moving = item)))

    fun remove(author: String, item: String): GroupOp = local(GroupOp(newId(), id, author, clock + 1, "remove", item = item))

    fun rename(author: String, title: String): GroupOp = local(GroupOp(newId(), id, author, clock + 1, "rename", title = title))

    /**
     * The operations that turn the current list into [wanted] (songs matched by id, in order, duplicates allowed): removes
     * for songs gone, adds for new ones, and moves only for songs outside the longest run already in order.
     */
    fun edit(author: String, wanted: List<JSONObject>, title: String?): List<GroupOp> {
        val ops = mutableListOf<GroupOp>()
        title?.trim()?.take(100)?.takeIf { it.isNotEmpty() && it != this.title }?.let { ops += rename(author, it) }
        val current = items()
        val pool = current.groupBy { it.track.optString("id") }.mapValues { it.value.toMutableList() }
        val matched = wanted.map { track -> pool[track.optString("id")]?.removeFirstOrNull() }
        val kept = matched.filterNotNull().map { it.item }.toSet()
        current.filter { it.item !in kept }.forEach { ops += remove(author, it.item) }
        val oldIndex = current.withIndex().associate { it.value.item to it.index }
        val inOrder = longestIncreasing(matched.filterNotNull().map { oldIndex.getValue(it.item) })
        var previous: String? = null
        wanted.forEachIndexed { i, track ->
            val entry = matched[i]
            previous = when {
                entry == null -> add(author, track, after = previous).also { ops += it }.item
                oldIndex.getValue(entry.item) !in inOrder -> { ops += move(author, entry.item, after = previous); entry.item }
                else -> entry.item
            }
        }
        return ops
    }

    /** The values of a longest strictly increasing subsequence. */
    private fun longestIncreasing(values: List<Int>): Set<Int> {
        if (values.isEmpty()) return emptySet()
        val tails = mutableListOf<Int>() // index into values of the smallest tail for each length
        val parent = IntArray(values.size) { -1 }
        values.forEachIndexed { i, v ->
            var lo = 0; var hi = tails.size
            while (lo < hi) { val mid = (lo + hi) / 2; if (values[tails[mid]] < v) lo = mid + 1 else hi = mid }
            if (lo > 0) parent[i] = tails[lo - 1]
            if (lo == tails.size) tails += i else tails[lo] = i
        }
        val result = mutableSetOf<Int>()
        var k = tails.last()
        while (k >= 0) { result += values[k]; k = parent[k] }
        return result
    }

    /** Takes in everything [other] knows (a member's snapshot), by the same rules as its operations. */
    fun merge(other: SharedPlaylistDoc) {
        if (other.id != id) return
        seen += other.seen
        clock = maxOf(clock, other.clock)
        if (later(other.titleClock, other.titleAuthor, titleClock, titleAuthor)) {
            title = other.title; titleClock = other.titleClock; titleAuthor = other.titleAuthor
        }
        other.slots.forEach { (key, theirs) ->
            val mine = slots.getOrPut(key) { Slot() }
            if (mine.track == null && theirs.track != null) { mine.track = theirs.track; mine.addedBy = theirs.addedBy }
            place(mine, theirs.pos, theirs.stampClock, theirs.stampAuthor)
            if (theirs.removed) mine.removed = true
        }
    }

    private fun local(op: GroupOp): GroupOp { apply(op); return op }
    private fun newId() = UUID.randomUUID().toString()

    /** A key just after [after] (null: at the start), between it and the next item. */
    private fun positionAfter(after: String?, moving: String?): String {
        val list = items().filter { it.item != moving }
        if (after == null) return Positions.between(null, list.firstOrNull()?.pos)
        val index = list.indexOfFirst { it.item == after }
        if (index < 0) return Positions.between(list.lastOrNull()?.pos, null)
        return Positions.between(list[index].pos, list.getOrNull(index + 1)?.pos)
    }

    /** The whole state, tombstones and seen operations included, for new members and relays losing old events. */
    fun snapshot(): JSONObject = JSONObject().put("id", id).put("members", JSONArray(members.toList())).put("title", title)
        .put("titleClock", titleClock).put("titleAuthor", titleAuthor).put("clock", clock).put("seen", JSONArray(seen.toList()))
        .put("slots", JSONObject(slots.mapValues { (_, s) ->
            JSONObject().put("addedBy", s.addedBy ?: JSONObject.NULL).put("pos", s.pos ?: JSONObject.NULL).put("clock", s.stampClock)
                .put("author", s.stampAuthor).put("removed", s.removed).apply { s.track?.let { put("track", it) } }
        }))

    companion object {
        fun fromSnapshot(json: JSONObject): SharedPlaylistDoc {
            val members = json.optJSONArray("members")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty().toSet()
            return SharedPlaylistDoc(json.getString("id"), members).apply {
                title = json.optString("title"); titleClock = json.optLong("titleClock", -1); titleAuthor = json.optString("titleAuthor")
                clock = json.optLong("clock")
                json.optJSONArray("seen")?.let { a -> (0 until a.length()).forEach { seen += a.getString(it) } }
                json.optJSONObject("slots")?.let { all ->
                    all.keys().forEach { key ->
                        val s = all.getJSONObject(key)
                        slots[key] = Slot(s.optJSONObject("track"), s.optString("addedBy").takeUnless { s.isNull("addedBy") },
                            s.optString("pos").takeUnless { s.isNull("pos") }, s.optLong("clock", -1), s.optString("author"), s.optBoolean("removed"))
                    }
                }
            }
        }
    }
}

/**
 * Who is in a shared playlist and the key its edits are encrypted with. Edits are posted under [tag], derived from the
 * key, so relays cannot link epochs; removing a member makes a new key (epoch) that only the others receive.
 */
data class SharedGroup(val id: String, val title: String, val owner: String, val members: List<String>, val epoch: Int, val keyHex: String) {
    val tag: String get() = PhotoCrypto.sha256(("spotifire-group:$keyHex").toByteArray()).take(32)

    fun without(member: String) = copy(members = members - member, epoch = epoch + 1, keyHex = newKey())
    fun with(member: String) = if (member in members) this else copy(members = members + member)

    fun seal(json: JSONObject): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyHex.hexToBytes(), "AES"), GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(json.toString().toByteArray()))
    }

    fun open(text: String): JSONObject? = runCatching {
        val bytes = Base64.getDecoder().decode(text)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHex.hexToBytes(), "AES"), GCMParameterSpec(128, bytes, 0, 12))
        JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12)))
    }.getOrNull()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("owner", owner).put("members", JSONArray(members))
        .put("epoch", epoch).put("key", keyHex)

    companion object {
        fun create(id: String, title: String, owner: String, members: List<String>) = SharedGroup(id, title, owner, members.distinct(), 0, newKey())
        fun fromJson(json: JSONObject): SharedGroup? = runCatching {
            SharedGroup(json.getString("id"), json.optString("title"), json.getString("owner"),
                json.getJSONArray("members").let { a -> (0 until a.length()).map(a::getString) }, json.getInt("epoch"),
                json.getString("key").takeIf { it.length == 64 }!!)
        }.getOrNull()
        private fun newKey() = ByteArray(32).also(SecureRandom()::nextBytes).toHex()
    }
}
