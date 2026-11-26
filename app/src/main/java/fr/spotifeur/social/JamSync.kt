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
    /** Play at [rate] (within ±0.1%, inaudible) until the gap closes. */
    data class Trim(val rate: Float) : DriftAction
    /** A real gap (a rebuffer, a late join): jump once to where the host is. */
    data object Seek : DriftAction
}

/**
 * What a guest does about being off the host, from its last readings (actual minus expected, ms). The median of the
 * last five, so one bad reading never moves anything: under 15 ms nothing; up to 150 ms an inaudible rate trim in
 * proportion (at most 0.1%, about 1 ms closed per second, many times the drift between two phones' clocks); beyond,
 * a seek, which only a glitch causes.
 */
object JamDrift {
    const val DEADBAND_MS = 15L
    const val SEEK_MS = 150L

    fun decide(offsets: List<Long>): DriftAction {
        val recent = offsets.takeLast(5)
        if (recent.isEmpty()) return DriftAction.None
        val median = recent.sorted()[recent.size / 2]
        return when {
            kotlin.math.abs(median) < DEADBAND_MS -> DriftAction.None
            kotlin.math.abs(median) <= SEEK_MS -> DriftAction.Trim((1f - median / 20_000f).coerceIn(0.999f, 1.001f))
            else -> DriftAction.Seek
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
