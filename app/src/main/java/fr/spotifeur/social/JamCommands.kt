package fr.spotifeur.social

import org.json.JSONObject

/** Something a Jam member did to the music; guests send it to the host, which applies it for everyone. */
sealed interface JamCommand {
    data object Play : JamCommand
    data object Pause : JamCommand
    data class Seek(val positionMs: Long) : JamCommand
    data object Next : JamCommand
    data object Previous : JamCommand
    /** Play [track] and go on with [next] (the rest of the album or playlist it was picked from). */
    data class PlayTrack(val track: JSONObject, val next: List<JSONObject> = emptyList()) : JamCommand
    data class Add(val track: JSONObject) : JamCommand

    /** How the others read it: "Nova skipped". */
    val said: String get() = when (this) {
        Play -> "played"
        Pause -> "paused"
        is Seek -> "moved to ${positionMs / 60_000}:%02d".format(positionMs / 1000 % 60)
        Next -> "skipped"
        Previous -> "went back"
        is PlayTrack -> "played ${track.optString("title", "a song")}"
        is Add -> "added ${track.optString("title", "a song")}"
    }

    fun toJson(): JSONObject = JSONObject().put("type", "cmd").apply {
        when (val c = this@JamCommand) {
            Play -> put("do", "play")
            Pause -> put("do", "pause")
            is Seek -> put("do", "seek").put("pos", c.positionMs)
            Next -> put("do", "next")
            Previous -> put("do", "previous")
            is PlayTrack -> put("do", "play-track").put("track", c.track).put("next", org.json.JSONArray(c.next))
            is Add -> put("do", "add").put("track", c.track)
        }
    }

    companion object {
        /** Null for anything unknown or incomplete: a command from a newer or broken client is ignored. */
        fun parse(json: JSONObject): JamCommand? {
            fun valid(t: JSONObject?) = t?.takeIf { it.optString("id").let { id -> id.startsWith("spotify:track:") || id.startsWith("ytm:track:") } }
            fun track() = valid(json.optJSONObject("track"))
            fun next() = json.optJSONArray("next")?.let { a -> (0 until minOf(a.length(), 50)).mapNotNull { valid(a.optJSONObject(it)) } }.orEmpty()
            return when (json.optString("do")) {
                "play" -> Play
                "pause" -> Pause
                "seek" -> if (json.has("pos")) Seek(json.optLong("pos").coerceAtLeast(0)) else null
                "next" -> Next
                "previous" -> Previous
                "play-track" -> track()?.let { PlayTrack(it, next()) }
                "add" -> track()?.let(::Add)
                else -> null
            }
        }
    }
}

/** Takes a guest's music action in place of the local player while the user is a guest in a Jam. */
fun interface GuestControl {
    /** True when the host takes it; false when the host has gone quiet and the phone should act on its own. */
    fun send(command: JamCommand): Boolean
}

/**
 * The host's side: applies each member's command to the player once (commands carry an id; relays may deliver one
 * twice) in arrival order, so the last one wins, and remembers who did what for everyone to see.
 */
class JamCommandApplier(private val remember: Int = 200) {
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > remember
    }

    /** (member's key, what they did), for the line under the Jam. */
    var lastAction: Pair<String, String>? = null
        private set

    /** Applies [body] from [from]; false when it was already applied or is not a command. Call on the main thread. */
    fun apply(player: JamPlayer, from: String, body: JSONObject): Boolean {
        val id = body.optString("cid").takeIf(String::isNotEmpty) ?: return false
        if (seen.containsKey(id)) return false
        val command = JamCommand.parse(body) ?: return false
        seen[id] = true
        when (command) {
            JamCommand.Play -> player.resume()
            JamCommand.Pause -> player.pause()
            is JamCommand.Seek -> player.seek(command.positionMs)
            JamCommand.Next -> player.next()
            JamCommand.Previous -> player.previous()
            is JamCommand.PlayTrack -> player.playQueue(listOf(command.track) + command.next, 0, start = true)
            is JamCommand.Add -> player.addToQueue(command.track)
        }
        lastAction = from to command.said
        return true
    }

    /** The host's own action, seen in its playback rather than received. */
    fun noteOwn(me: String, what: String) { lastAction = me to what }
}
