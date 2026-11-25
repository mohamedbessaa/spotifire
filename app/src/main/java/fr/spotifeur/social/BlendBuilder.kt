package fr.spotifeur.social

import org.json.JSONObject
import java.util.Random

/**
 * A Blend: 50 songs from two tastes (up to 200 liked and most played songs each). Songs both like come first (the same
 * recording counts once, matched by ISRC or id); then the two sides alternate, each in its most-played order shuffled
 * within play-count bands by the date, so the Blend changes daily. Both phones build the same list: the sides are
 * ordered by public key, never by "me" and "them".
 */
object BlendBuilder {
    const val SIZE = 50
    private val VALID = Regex("""spotify:track:[A-Za-z0-9]{22}|ytm:track:[A-Za-z0-9_-]{11}""")

    fun build(a: Pair<String, List<JSONObject>>, b: Pair<String, List<JSONObject>>, date: String, size: Int = SIZE): List<JSONObject> {
        val (first, second) = listOf(a, b).sortedBy { it.first }
        val x = clean(first.second); val y = clean(second.second)
        val yIds = y.map { it.getString("id") }.toSet()
        val yIsrcs = y.mapNotNull(::isrc).toSet()
        fun inY(t: JSONObject) = t.getString("id") in yIds || isrc(t)?.let { it in yIsrcs } == true
        fun partner(t: JSONObject) = y.firstOrNull { it.getString("id") == t.getString("id") || isrc(t) != null && isrc(it) == isrc(t) }
        val shared = x.filter(::inY).sortedWith(compareByDescending<JSONObject> { plays(it) + (partner(it)?.let(::plays) ?: 0) }.thenBy { it.getString("id") })
        val used = HashSet<String>()
        fun mark(t: JSONObject) { used += t.getString("id"); isrc(t)?.let { used += "isrc:$it" } }
        fun isUsed(t: JSONObject) = t.getString("id") in used || isrc(t)?.let { "isrc:$it" in used } == true
        val result = mutableListOf<JSONObject>()
        for (t in shared) { if (result.size >= size) break; result += t; mark(t); partner(t)?.let(::mark) }
        val seed = (date + first.first + second.first).hashCode().toLong()
        val queues = listOf(daily(x, seed), daily(y, seed + 1)).map { ArrayDeque(it) }
        var side = if (Random(seed).nextBoolean()) 0 else 1
        while (result.size < size && queues.any { it.isNotEmpty() }) {
            val queue = queues[side]
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                if (!isUsed(t)) { result += t; mark(t); break }
            }
            side = 1 - side
        }
        return result
    }

    private fun clean(list: List<JSONObject>) = list.filter { it.optString("id").matches(VALID) }
        .sortedWith(compareByDescending<JSONObject>(::plays).thenBy { it.getString("id") }).distinctBy { it.getString("id") }.take(200)

    /** Most played first, shuffled by the day within bands of similar play counts. */
    private fun daily(list: List<JSONObject>, seed: Long) =
        list.shuffled(Random(seed)).sortedByDescending { 31 - Integer.numberOfLeadingZeros(plays(it) + 1) }

    private fun plays(t: JSONObject) = t.optInt("plays").coerceAtLeast(0)
    private fun isrc(t: JSONObject) = t.optString("isrc").takeIf { it.matches(Regex("[A-Z0-9]{12}")) }
}
