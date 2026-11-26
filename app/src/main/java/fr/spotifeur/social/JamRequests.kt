package fr.spotifeur.social

/** A friend asking to listen to what this phone plays. */
data class JoinRequest(val from: String, val at: Long)

/**
 * Requests to join this phone's music: only from friends, one per friend (a repeat is the same request), and gone
 * after [ttlMs] so an old ask never pops up later.
 */
class JoinRequests(private val ttlMs: Long = 2 * 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    private val items = LinkedHashMap<String, JoinRequest>()

    /**
     * True when [from]'s request is new and should be shown. [sentAtMs] is when it was sent: relays deliver old
     * messages again after a restart, and a request older than [ttlMs] is not new.
     */
    @Synchronized fun receive(from: String, isFriend: Boolean, sentAtMs: Long = now()): Boolean {
        if (!isFriend || now() - sentAtMs > ttlMs) return false
        prune()
        if (items.containsKey(from)) return false
        items[from] = JoinRequest(from, minOf(sentAtMs, now()))
        return true
    }

    @Synchronized fun list(): List<JoinRequest> { prune(); return items.values.toList() }

    @Synchronized fun remove(from: String) { items.remove(from) }

    /** Takes [from]'s request to answer it; false when there is none any more (expired, or already answered). */
    @Synchronized fun take(from: String): Boolean { prune(); return items.remove(from) != null }

    private fun prune() { val limit = now() - ttlMs; items.values.removeAll { it.at < limit } }
}

/** One ask per friend every [gapMs], so a tap-happy finger does not flood their phone. */
class AskPace(private val gapMs: Long = 30_000L, private val now: () -> Long = System::currentTimeMillis) {
    private val last = HashMap<String, Long>()

    /** Whether this phone asked [friend] in the last [answerMs]: their invitation is then joined without a tap. */
    @Synchronized fun askedRecently(friend: String, answerMs: Long = 2 * 60_000L): Boolean =
        last[friend]?.let { now() - it <= answerMs } == true

    /** The ask was answered (joined): a later invitation from [friend] needs a tap again. */
    @Synchronized fun answered(friend: String) { last.remove(friend) }

    @Synchronized fun mayAsk(friend: String): Boolean {
        val at = now()
        if (last[friend]?.let { at - it < gapMs } == true) return false
        last[friend] = at
        return true
    }
}
