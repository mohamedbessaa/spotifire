package fr.spotifeur.social

/**
 * The guest's estimate of the host clock: each ping gives (sent, host time, received); the sample with the shortest
 * round trip among the last [window] gives the offset, assuming the trip was even both ways.
 */
class JamClock(private val window: Int = 8) {
    private data class Sample(val roundTrip: Long, val offset: Long)
    private val samples = ArrayDeque<Sample>()

    fun sample(sentAt: Long, hostTime: Long, receivedAt: Long) {
        val roundTrip = receivedAt - sentAt
        // A late answer to an old ping says nothing about the clock now.
        if (roundTrip < 0 || roundTrip > 5_000) return
        samples.addLast(Sample(roundTrip, hostTime - (sentAt + roundTrip / 2)))
        while (samples.size > window) samples.removeFirst()
    }

    private fun best() = samples.minByOrNull { it.roundTrip }
    val offsetMs: Long? get() = best()?.offset
    val bestRoundTripMs: Long? get() = best()?.roundTrip
    fun hostTime(localNow: Long): Long = localNow + (offsetMs ?: 0)
    fun localTime(hostTime: Long): Long = hostTime - (offsetMs ?: 0)
}

sealed interface DriftAction {
    data object None : DriftAction
    data class Speed(val rate: Float) : DriftAction
    data class Seek(val positionMs: Long) : DriftAction
}

/**
 * What a guest does about being off the host: under 20 ms nothing; up to 250 ms a speed change in proportion, closing
 * the gap in about 2.5 s and never beyond ±3% (inaudible); beyond, a seek.
 */
object JamDrift {
    fun decide(expectedMs: Long, actualMs: Long): DriftAction {
        val off = actualMs - expectedMs
        return when {
            kotlin.math.abs(off) < 20 -> DriftAction.None
            kotlin.math.abs(off) <= 250 -> DriftAction.Speed((1f - off / 2500f).coerceIn(0.97f, 1.03f))
            else -> DriftAction.Seek(expectedMs)
        }
    }
}

/** The host's playback as last announced: where it was at [atHostTime], or a start scheduled for [startAtHostTime]. */
data class JamState(
    val session: String, val trackId: String, val positionMs: Long, val atHostTime: Long, val playing: Boolean, val startAtHostTime: Long?,
) {
    fun positionAt(hostNow: Long): Long = when {
        startAtHostTime != null -> if (hostNow < startAtHostTime) positionMs else positionMs + (hostNow - startAtHostTime)
        playing -> positionMs + (hostNow - atHostTime)
        else -> positionMs
    }
}

/** Only the session joined, and only its members, can reach the player. */
class JamGuard {
    private var session: String? = null
    private var members: Set<String> = emptySet()
    var host: String? = null; private set

    fun joined(session: String, host: String, members: Set<String>) { this.session = session; this.host = host; this.members = members }
    fun left() { session = null; host = null; members = emptySet() }
    fun accepts(session: String, from: String) = session == this.session && from in members
    val current: String? get() = session
}
