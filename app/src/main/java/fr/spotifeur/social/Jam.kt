package fr.spotifeur.social

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.os.SystemClock

/** What the Jam needs from the app's player; implemented by the app and always called on the main thread. */
interface JamPlayer {
    /** [readAt]: the monotonic time (elapsedRealtime) at which [positionMs] was read. */
    data class Now(
        val track: JSONObject, val positionMs: Long, val playing: Boolean, val readAt: Long = android.os.SystemClock.elapsedRealtime(),
        /** Playback speed, and where the reading came from ("exo": the rendering player; "ctl": the controller's estimate). */
        val rate: Float = 1f, val source: String = "ctl", val rawMs: Long = positionMs,
    )
    fun now(): Now?
    fun upNext(): List<JSONObject>
    fun play(track: JSONObject, positionMs: Long, start: Boolean)
    fun pause()
    fun resume()
    fun seek(positionMs: Long)
    fun speed(rate: Float)
    fun addToQueue(track: JSONObject)
    /** Keeps the guest's own queue aside when joining, and puts it back on leaving. */
    fun saveQueue()
    fun restoreQueue()
}

data class JamInvite(val session: String, val from: String, val title: String, val subtitle: String, val image: String?, val at: Long)

/** The Jam as the screens show it. */
data class JamView(
    val session: String, val host: String, val hosting: Boolean, val members: List<String>, val joined: Set<String>,
    val track: JSONObject?, val playing: Boolean, val allowQueue: Boolean, val offsetKnown: Boolean,
)

/**
 * Listen together. The host's phone is the clock: it announces what plays (ephemeral, encrypted with the session key)
 * on every change and every 5 s. Guests estimate the host clock from ping round trips, start tracks at the announced
 * host time and correct drift. Joining needs the invitation and an explicit Join; leaving restores the guest's queue.
 */
object Jam {
    var player: JamPlayer? = null
    private var group: SharedGroup? = null
    private var hosting = false
    private val guard = JamGuard()
    private var clock = JamClock()
    private var state: JamState? = null
    private var stateTrack: JSONObject? = null
    private var allowQueue = false
    private var joined = emptySet<String>()
    private var lastFromHost = 0L
    private var joinedAt = 0L
    private var scheduledStart: Long? = null
    /** How far ahead a guest seeks so the audio lands on time once buffered; learned after each seek. */
    private var seekLead = 150L
    private var seekedAt: Long? = null
    private var loop: Job? = null
    private val invites = LinkedHashMap<String, Pair<JamInvite, JSONObject>>()
    private val mutableView = MutableStateFlow<JamView?>(null)
    val view: StateFlow<JamView?> = mutableView.asStateFlow()
    private val mutableInvites = MutableStateFlow<List<JamInvite>>(emptyList())
    val pendingInvites: StateFlow<List<JamInvite>> = mutableInvites.asStateFlow()

    internal fun watched(): List<SharedGroup> = listOfNotNull(group)
    private fun now() = SystemClock.elapsedRealtime()

    /** This phone's own position, filtered (see [PositionFilter]); every reading goes through it. */
    private val ownPosition = PositionFilter()
    private fun observe(n: JamPlayer.Now?): JamPlayer.Now? {
        n ?: return null
        ownPosition.add(n.readAt, n.positionMs, n.rate, n.playing, n.track.optString("id"))
        val filtered = ownPosition.estimate(n.readAt)?.let { Math.round(it) } ?: n.positionMs
        return n.copy(positionMs = filtered, rawMs = n.positionMs)
    }

    // Session time. The host announces true time (time servers) when it has it, so relay delays do not matter; the
    // guest maps it with its own time-server offset. Without time servers, the old way: host clock from relay pings.
    /** What the host's last update said its clock is; ours is used with it as soon as our own sync is done. */
    private var hostClockNtp = false
    private val hostUsesNtp get() = hostClockNtp && NtpClock.offset != null
    private fun hostTime(): Long = NtpClock.utcNow() ?: now()
    /** The session time of a moment on this phone's monotonic clock. */
    private fun hostTimeAt(local: Long): Long = NtpClock.offset?.let { local + it } ?: local
    private fun hostEstimateAt(local: Long): Long? =
        if (hostUsesNtp) NtpClock.offset?.let { local + it } else clock.offsetMs?.let { clock.hostTime(local) }
    private fun hostNowEstimate(): Long? =
        if (hostUsesNtp) NtpClock.utcNow() else clock.offsetMs?.let { clock.hostTime(now()) }
    private fun localTimeOf(hostT: Long): Long? =
        if (hostUsesNtp) NtpClock.offset?.let { hostT - it } else clock.offsetMs?.let { clock.localTime(hostT) }
    private fun clockMode() = if (hostUsesNtp) "ntp rtt=${NtpClock.roundTrip}" else "relay rtt=${clock.bestRoundTripMs}"

    /**
     * One line a second while a Jam runs: wall clock, exact position, track and decoded level. Compared between two
     * devices on a common clock, it measures the real sync (see the Jam sync check in the ledger).
     */
    private suspend fun logSync(role: String) {
        val p = withContext(Dispatchers.Main) { observe(player?.now()) } ?: return
        val wall = System.currentTimeMillis() - (now() - p.readAt)
        android.util.Log.i("SpotifeurJamSync", "$role wall=$wall pos=${p.rawMs} fpos=${p.positionMs} src=${p.source} playing=${p.playing} " +
            "db=${"%.1f".format(fr.spotifeur.playback.dsp.EnergyMeter.lastRenderedDb)} track=${p.track.optString("id")}")
    }
    private fun me() = Social.snapshot.value.me

    // Host --------------------------------------------------------------------------------------------------

    /** Starts a Jam on what is playing now and invites [friends]; they choose to join. */
    suspend fun start(friends: List<String>, allowGuestsToQueue: Boolean) {
        val me = me() ?: return
        val what = withContext(Dispatchers.Main) { player?.now() } ?: return
        stopAll(notify = true)
        val session = SharedGroup.create(java.util.UUID.randomUUID().toString(), what.track.optString("title"), me, listOf(me) + friends)
        group = session; hosting = true; allowQueue = allowGuestsToQueue; joined = emptySet()
        guard.joined(session.id, me, session.members.toSet())
        Social.rewatchGroups()
        val invite = JSONObject().put("type", "jam-invite").put("group", session.toJson())
            .put("what", JSONObject().put("title", what.track.optString("title")).put("subtitle", what.track.optString("subtitle"))
                .apply { what.track.optString("image").takeIf { it.startsWith("https://") }?.let { put("image", it) } })
        Social.deliverTo(friends, invite)
        loop = Social.launchJob { NtpClock.sync(); hostLoop() }
        publishView()
    }

    private suspend fun hostLoop() {
        var last: JamPlayer.Now? = null
        var lastSent = 0L
        var lastNtp = now()
        var lastAt = now()
        while (kotlin.coroutines.coroutineContext.isActive && hosting) {
            val nowPlaying = withContext(Dispatchers.Main) { observe(player?.now()) }
            if (nowPlaying != null) {
                val trackChanged = last?.track?.optString("id") != nowPlaying.track.optString("id")
                val toggled = last?.playing != nowPlaying.playing
                val elapsed = now() - lastAt
                val jumped = last != null && !trackChanged && kotlin.math.abs(nowPlaying.positionMs - (last.positionMs + if (last.playing) elapsed else 0)) > 1500
                if (trackChanged && last != null && nowPlaying.playing) {
                    // A new track starts on both phones together: hold it 1.5 s while the guests load theirs.
                    withContext(Dispatchers.Main) { player?.pause(); player?.seek(0) }
                    val startAt = hostTime() + START_LEAD_MS
                    announce(nowPlaying.track, 0, playing = true, startAt = startAt)
                    delay(START_LEAD_MS)
                    withContext(Dispatchers.Main) { player?.resume() }
                    lastSent = now()
                    last = nowPlaying.copy(positionMs = 0); lastAt = now()
                    publishView(nowPlaying.track, true)
                    continue
                }
                if (trackChanged || toggled || jumped || now() - lastSent > HEARTBEAT_MS) {
                    announce(nowPlaying.track, nowPlaying.positionMs, nowPlaying.playing, startAt = null, at = hostTimeAt(nowPlaying.readAt))
                    lastSent = now()
                    publishView(nowPlaying.track, nowPlaying.playing)
                }
                last = nowPlaying
                lastAt = now()
            }
            logSync("host clock=${if (NtpClock.offset != null) "ntp rtt=${NtpClock.roundTrip}" else "mono"}")
            if (now() - lastNtp > NTP_EVERY_MS) { lastNtp = now(); NtpClock.sync() }
            delay(500)
        }
    }

    private suspend fun announce(track: JSONObject, positionMs: Long, playing: Boolean, startAt: Long?, at: Long = hostTime()) {
        val session = group ?: return
        val upNext = withContext(Dispatchers.Main) { player?.upNext().orEmpty() }.take(20)
        Social.messengerOrNull()?.publishGroup(session, JSONObject().put("type", "state").put("track", track).put("pos", positionMs)
            .put("at", at).put("clock", if (NtpClock.offset != null) "ntp" else "mono").put("playing", playing).put("allowQueue", allowQueue).put("joined", JSONArray(joined.toList()))
            .put("queue", JSONArray(upNext)).apply { startAt?.let { put("startAt", it) } }, live = true)
    }

    fun setAllowQueue(allowed: Boolean) { allowQueue = allowed; publishView() }

    // Guest -------------------------------------------------------------------------------------------------

    suspend fun join(context: Context, session: String) {
        val body = invites.remove(session)?.second ?: return
        val next = SharedGroup.fromJson(body.getJSONObject("group")) ?: return
        publishInvites()
        stopAll(notify = true)
        withContext(Dispatchers.Main) { player?.saveQueue() }
        group = next; hosting = false; clock = JamClock(); state = null; lastFromHost = now(); hostClockNtp = false; joinedAt = now()
        guard.joined(next.id, next.owner, next.members.toSet())
        Social.rewatchGroups()
        loop = Social.launchJob { NtpClock.sync(); guestLoop() }
        publishView()
    }

    suspend fun decline(session: String) {
        val invite = invites.remove(session)?.first ?: return
        publishInvites()
        Social.deliverTo(listOf(invite.from), JSONObject().put("type", "jam-decline").put("session", session))
    }

    private suspend fun guestLoop() {
        val session = group ?: return
        var tick = 0
        while (kotlin.coroutines.coroutineContext.isActive && !hosting && group?.id == session.id) {
            // Clock: five quick pings at first, then one every 30 s.
            // Relay pings only matter when the host has no time-server clock; a few are sent anyway as a fallback.
            if (tick < 10 && tick % 2 == 0 || tick % 60 == 0) Social.messengerOrNull()?.publishGroup(session,
                JSONObject().put("type", "ping").put("t0", now()), live = true)
            if (tick % 20 == 0) Social.messengerOrNull()?.publishGroup(session, JSONObject().put("type", "hello"), live = true)
            if (tick > 0 && tick % 120 == 0) NtpClock.sync()
            follow()
            logSync("guest clock=${clockMode()}")
            if (now() - lastFromHost > HOST_GONE_MS) { leave(); return }
            // Joined a Jam whose host never answers (it already ended): leave instead of waiting in silence.
            if (state == null && now() - joinedAt > NO_HOST_MS) { android.util.Log.i("SpotifeurJamSync", "no host answer, leaving"); leave(); return }
            tick++
            delay(500)
        }
    }

    /** Makes the guest's player match the host's last announcement. */
    private suspend fun follow() {
        val target = state ?: return
        val track = stateTrack ?: return
        val hostNow = hostNowEstimate() ?: return
        withContext(Dispatchers.Main) {
            val p = player ?: return@withContext
            val current = observe(p.now())
            // Expected where the host is at the very moment our own position was read.
            // Never past the end: a bad estimate must not run the song out.
            val end = track.optLong("durationMs").takeIf { it > 0 }?.let { it - 500 } ?: Long.MAX_VALUE
            val expected = target.positionAt(hostEstimateAt(current?.readAt ?: now()) ?: hostNow).coerceIn(0, end)
            val shouldPlay = target.startAtHostTime?.let { hostNow >= it } ?: target.playing
            if (current?.track?.optString("id") != target.trackId) {
                p.play(track, expected + seekLead, start = shouldPlay); seekedAt = now()
                return@withContext
            }
            if (!shouldPlay) { if (current.playing) p.pause(); if (kotlin.math.abs(current.positionMs - expected) > 250) p.seek(expected); p.speed(1f); return@withContext }
            if (!current.playing) { p.seek(expected + seekLead); p.resume(); seekedAt = now(); return@withContext }
            val off = current.positionMs - expected
            // Learn how long a seek takes to sound: the first reading 1-3 s after it says how far behind it landed.
            seekedAt?.let { at -> if (now() - at in 1000..3000) { seekLead = (seekLead - off / 2).coerceIn(0, 1500); seekedAt = null } }
            when (val action = JamDrift.decide(expected, current.positionMs)) {
                DriftAction.None -> p.speed(1f)
                is DriftAction.Speed -> p.speed(action.rate)
                is DriftAction.Seek -> { p.seek(action.positionMs + seekLead); p.speed(1f); seekedAt = now() }
            }
        }
        // A scheduled start is met at the exact moment, not at the next tick.
        target.startAtHostTime?.takeIf { it != scheduledStart }?.let { startAt ->
            val wait = (localTimeOf(startAt) ?: return) - now()
            if (wait in 1..1500) Social.launchJob {
                scheduledStart = startAt
                delay(wait)
                withContext(Dispatchers.Main) { player?.let { p -> if (p.now()?.track?.optString("id") == target.trackId) { p.seek(target.positionMs); p.resume() } } }
            }
        }
    }

    /** A guest's request to add a song; the host adds it only when guests may. */
    suspend fun suggest(track: JSONObject) {
        val session = group ?: return
        if (hosting) { withContext(Dispatchers.Main) { player?.addToQueue(track) }; return }
        Social.messengerOrNull()?.publishGroup(session, JSONObject().put("type", "queue-add").put("track", track), live = true)
    }

    suspend fun leave() {
        val session = group ?: return
        if (hosting) Social.messengerOrNull()?.publishGroup(session, JSONObject().put("type", "end"), live = true)
        else {
            Social.messengerOrNull()?.publishGroup(session, JSONObject().put("type", "bye"), live = true)
            withContext(Dispatchers.Main) { player?.speed(1f); player?.restoreQueue() }
        }
        stopAll(notify = false)
    }

    private suspend fun stopAll(notify: Boolean) {
        if (notify && group != null) {
            val session = group!!
            Social.messengerOrNull()?.publishGroup(session, JSONObject().put("type", if (hosting) "end" else "bye"), live = true)
            if (!hosting) withContext(Dispatchers.Main) { player?.speed(1f); player?.restoreQueue() }
        }
        loop?.cancel(); loop = null
        group = null; hosting = false; state = null; stateTrack = null; joined = emptySet()
        guard.left()
        Social.rewatchGroups()
        publishView()
    }

    // Messages ----------------------------------------------------------------------------------------------

    internal suspend fun onDirect(context: Context, message: Messenger.Incoming, isFriend: Boolean) {
        val body = message.body
        when (body.optString("type")) {
            "jam-invite" -> {
                if (!isFriend) return
                val next = SharedGroup.fromJson(body.optJSONObject("group") ?: return) ?: return
                if (next.owner != message.from || me() !in next.members) return
                // An invitation is for right now: relays may deliver an old one again (after a reinstall, say).
                if (System.currentTimeMillis() / 1000 - message.sentAt > INVITE_FRESH_S) return
                val what = body.optJSONObject("what") ?: JSONObject()
                val invite = JamInvite(next.id, message.from, what.optString("title"), what.optString("subtitle"),
                    what.optString("image").takeIf { it.startsWith("https://") }, message.sentAt)
                if (invites.containsKey(next.id)) return
                invites[next.id] = invite to body
                publishInvites()
                SocialNotifier.jamInvite(context, Social.nameOf(message.from), invite.title)
            }
            "jam-decline" -> Unit
        }
    }

    internal suspend fun onGroup(message: Messenger.GroupIncoming) {
        val session = group ?: return
        if (message.group.id != session.id || !guard.accepts(session.id, message.author) || message.author == me()) return
        val body = message.body
        if (hosting) when (body.optString("type")) {
            "ping" -> Social.messengerOrNull()?.publishGroup(session, JSONObject().put("type", "pong").put("to", message.author)
                .put("t0", body.optLong("t0")).put("host", now()), live = true)
            "hello" -> { if (message.author !in joined) { joined = joined + message.author; publishView() } }
            "bye" -> { joined = joined - message.author; publishView() }
            "queue-add" -> if (allowQueue) body.optJSONObject("track")?.let { t ->
                if (SharedItems.decode(JSONObject().put("kind", "song").put("title", t.optString("title", "Song")).put("id", t.optString("id"))) != null) {
                    withContext(Dispatchers.Main) { player?.addToQueue(t) }
                }
            }
        } else {
            if (message.author != guard.host) return
            lastFromHost = now()
            android.util.Log.i("SpotifeurJamSync", "guest got ${body.optString("type")} clock=${body.optString("clock")} pos=${body.optLong("pos")} playing=${body.optBoolean("playing")}")
            when (body.optString("type")) {
                "pong" -> if (body.optString("to") == me()) { clock.sample(body.optLong("t0"), body.optLong("host"), now()); publishView() }
                "state" -> {
                    val track = body.optJSONObject("track") ?: return
                    val id = track.optString("id").takeIf { it.startsWith("spotify:track:") || it.startsWith("ytm:track:") } ?: return
                    state = JamState(session.id, id, body.optLong("pos"), body.optLong("at"), body.optBoolean("playing"),
                        body.optLong("startAt").takeIf { body.has("startAt") })
                    hostClockNtp = body.optString("clock") == "ntp"
                    stateTrack = track
                    allowQueue = body.optBoolean("allowQueue")
                    joined = body.optJSONArray("joined")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty().toSet()
                    publishView(track, state!!.playing)
                    follow()
                }
                "end" -> leave()
            }
        }
    }

    private fun publishView(track: JSONObject? = mutableView.value?.track, playing: Boolean = mutableView.value?.playing ?: false) {
        val session = group
        mutableView.value = session?.let {
            JamView(it.id, it.owner, hosting, it.members, joined, track, playing, allowQueue, hosting || (if (hostUsesNtp) NtpClock.offset != null else clock.offsetMs != null))
        }
    }

    private fun publishInvites() { mutableInvites.value = invites.values.map { it.first } }

    private const val START_LEAD_MS = 1500L
    private const val HEARTBEAT_MS = 5000L
    private const val HOST_GONE_MS = 10 * 60 * 1000L
    private const val NTP_EVERY_MS = 60_000L
    private const val INVITE_FRESH_S = 90L
    private const val NO_HOST_MS = 20_000L
}
