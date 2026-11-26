package fr.spotifeur.social

/** A friend asking to listen to what this phone plays. */
data class JoinRequest(val from: String, val at: Long)

/**
 * Requests to join this phone's music: only from friends, one per friend (a repeat is the same request), and gone
 * after [ttlMs] so an old ask never pops up later.
 */
class JoinRequests(private val ttlMs: Long = 2 * 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    private val items = LinkedHashMap<String, JoinRequest>()

    /** True when [from]'s request is new and should be shown. */
    @Synchronized fun receive(from: String, isFriend: Boolean): Boolean {
        if (!isFriend) return false
        prune()
        if (items.containsKey(from)) return false
        items[from] = JoinRequest(from, now())
        return true
    }

    @Synchronized fun list(): List<JoinRequest> { prune(); return items.values.toList() }

    @Synchronized fun remove(from: String) { items.remove(from) }

    private fun prune() { val limit = now() - ttlMs; items.values.removeAll { it.at < limit } }
}

/** One ask per friend every [gapMs], so a tap-happy finger does not flood their phone. */
class AskPace(private val gapMs: Long = 30_000L, private val now: () -> Long = System::currentTimeMillis) {
    private val last = HashMap<String, Long>()

    @Synchronized fun mayAsk(friend: String): Boolean {
        val at = now()
        if (last[friend]?.let { at - it < gapMs } == true) return false
        last[friend] = at
        return true
    }
}
