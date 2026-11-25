package fr.spotifeur.social

import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Live check against the public relays and a real phone or emulator. Gated: SPOTIFIRE_LIVE=<step>, with the test
 * identity kept in SPOTIFIRE_LIVE_KEYS (a scratch file) between steps and SPOTIFIRE_LIVE_TARGET = name#CODE.
 */
class LiveFriendsTest {
    private val step = System.getenv("SPOTIFIRE_LIVE")

    @Test
    fun live() = runBlocking {
        assumeTrue(step != null)
        val file = File(System.getenv("SPOTIFIRE_LIVE_KEYS"))
        val keys = if (file.exists()) SocialKeys.fromStored(file.readText().trim(), null) else SocialKeys.create().also { file.writeText(it.privateKeyHex) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val messenger = Messenger(keys, BasicOkHttpWebSocket.Builder { OkHttpClient() }, scope)
        messenger.start(since = System.currentTimeMillis() / 1000 - 3600)
        delay(3000)
        val target = System.getenv("SPOTIFIRE_LIVE_TARGET")
        val found = Directory(messenger).lookup(target)
        println("LIVE lookup $target -> ${found.map { it.handle }}")
        val them = found.singleOrNull()?.pubKey ?: error("not found")
        when (step) {
            "request" -> {
                Directory(messenger).publish(keys, PublicProfile("Tester", null), discoverable = false)
                val t0 = System.currentTimeMillis()
                val ok = messenger.send(listOf(them), JSONObject().put("type", "friend-request").put("name", "Tester").put("note", "Live test from the PC"), pow = SocialState.REQUEST_POW)
                println("LIVE request sent=$ok mined+published in ${System.currentTimeMillis() - t0} ms")
            }
            "await-accept-then-share" -> {
                val accept = withTimeoutOrNull(120_000) { messenger.incoming.first { it.from == them && it.body.optString("type") == "friend-accept" } }
                println("LIVE accept received=${accept != null} name=${accept?.body?.optString("name")}")
                val share = JSONObject().put("type", "share").put("id", java.util.UUID.randomUUID().toString()).put("note", "Live share from the PC")
                    .put("item", JSONObject().put("kind", "song").put("id", "spotify:track:4cOdK2wGLETKBW3PvgPWqT").put("title", "Never Gonna Give You Up")
                        .put("subtitle", "Rick Astley").put("image", "https://i.scdn.co/image/ab67616d0000b27315ebbedaacef61af244262a8"))
                println("LIVE share sent=${messenger.send(listOf(them), share)}")
                val receipt = withTimeoutOrNull(120_000) { messenger.incoming.first { it.from == them && it.body.optString("type") == "receipt" } }
                println("LIVE receipt=${receipt?.body?.optString("state")}")
            }
            "blend" -> {
                val id = java.util.UUID.randomUUID().toString()
                println("LIVE blend invite sent=" + messenger.send(listOf(them), JSONObject().put("type", "blend-invite").put("id", id)))
                val accept = withTimeoutOrNull(240_000) { messenger.incoming.first { it.from == them && it.body.optString("type") == "blend-accept" && it.body.optString("id") == id } }
                val theirs = withTimeoutOrNull(120_000) { messenger.incoming.first { it.from == them && it.body.optString("type") == "blend-taste" && it.body.optString("id") == id } }
                val theirTracks = theirs?.body?.optJSONArray("tracks")?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty()
                println("LIVE blend accept=${accept != null} their taste=${theirTracks.size} songs: ${theirTracks.map { it.optString("title") }}")
                val ids = listOf("0VjIjW4GlUZAMYd2vXMi3b", "7qiZfU4dY1lWllzX7mPBI3", "3n3Ppam7vgaVa1iaRUc9Lp", "4cOdK2wGLETKBW3PvgPWqT", "2takcwOaAZWiXQijPHIx7B",
                    "1zi7xx7UVEFkmKfv06H8x0", "6habFhsOp2NvshLv26DqMb", "3KkXRkHbMCARz0aVfEt68P", "5ghIJDpPoe3CfHMGu71E6T", "7ouMYWpwJ422jRcDASZB7P")
                val mine = ids.mapIndexed { i, s -> JSONObject().put("id", "spotify:track:$s").put("title", "PC song ${i + 1}").put("plays", 50 - i * 3) }
                val date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
                println("LIVE taste sent=" + messenger.send(listOf(them), JSONObject().put("type", "blend-taste").put("id", id).put("date", date).put("tracks", org.json.JSONArray(mine))))
                val built = BlendBuilder.build(keys.publicKeyHex to mine, them to theirTracks, date)
                println("LIVE blend on the PC (${built.size}): ${built.map { it.optString("title") }}")
            }
            "jam-guest" -> {
                val invite = withTimeoutOrNull(300_000) { messenger.incoming.first { it.from == them && it.body.optString("type") == "jam-invite" } }
                val group = invite?.body?.optJSONObject("group")?.let(SharedGroup::fromJson) ?: error("no Jam invite")
                println("LIVE jam invite: ${invite.body.optJSONObject("what")}")
                messenger.watchGroups(listOf(group), since = System.currentTimeMillis() / 1000)
                delay(1500)
                val clock = JamClock()
                val pongs = mutableListOf<Long>()
                var states = 0
                val listener = scope.launch {
                    messenger.groupIncoming.collect { m ->
                        if (m.author != them) return@collect
                        when (m.body.optString("type")) {
                            "pong" -> if (m.body.optString("to") == keys.publicKeyHex) {
                                val now = System.nanoTime() / 1_000_000
                                clock.sample(m.body.optLong("t0"), m.body.optLong("host"), now); pongs += now - m.body.optLong("t0")
                            }
                            "state" -> { states++; if (states <= 3) println("LIVE jam state: ${m.body.optJSONObject("track")?.optString("title")} pos=${m.body.optLong("pos")} playing=${m.body.optBoolean("playing")} startAt=${m.body.opt("startAt")}") }
                        }
                    }
                }
                messenger.publishGroup(group, JSONObject().put("type", "hello"), live = true)
                repeat(10) {
                    messenger.publishGroup(group, JSONObject().put("type", "ping").put("t0", System.nanoTime() / 1_000_000), live = true)
                    delay(1500)
                }
                delay(5000)
                println("LIVE jam pongs=${pongs.size} round trips ms=${pongs.sorted()} best=${clock.bestRoundTripMs} offsetKnown=${clock.offsetMs != null} states=$states")
                messenger.publishGroup(group, JSONObject().put("type", "bye"), live = true)
                listener.cancel()
            }
            "playlist" -> {
                val me = keys.publicKeyHex
                val group = SharedGroup.create(java.util.UUID.randomUUID().toString(), "Live mix", me, listOf(me, them))
                val doc = SharedPlaylistDoc(group.id, group.members.toSet())
                doc.rename(me, "Live mix")
                val songs = listOf("4cOdK2wGLETKBW3PvgPWqT" to "Never Gonna Give You Up", "0VjIjW4GlUZAMYd2vXMi3b" to "Blinding Lights",
                    "7qiZfU4dY1lWllzX7mPBI3" to "Shape of You", "3n3Ppam7vgaVa1iaRUc9Lp" to "Mr. Brightside")
                fun track(i: Int) = JSONObject().put("id", "spotify:track:" + songs[i].first).put("title", songs[i].second).put("subtitle", "Artist")
                (0..2).forEach { doc.add(me, track(it), after = doc.items().lastOrNull()?.item) }
                messenger.watchGroups(listOf(group), since = System.currentTimeMillis() / 1000 - 60)
                println("LIVE invite sent=" + messenger.send(listOf(them), JSONObject().put("type", "playlist-invite").put("group", group.toJson()).put("snapshot", doc.snapshot())))
                val join = withTimeoutOrNull(240_000) { messenger.incoming.first { it.from == them && it.body.optString("type") == "playlist-join" } }
                println("LIVE join=${join != null}")
                val op = doc.add(me, track(3), after = doc.items().lastOrNull()?.item)
                println("LIVE op published=" + messenger.publishGroup(group, JSONObject().put("type", "ops").put("ops", org.json.JSONArray(listOf(op.toJson())))))
                val edit = withTimeoutOrNull(240_000) { messenger.groupIncoming.first { it.author == them } }
                edit?.body?.optJSONArray("ops")?.let { a -> (0 until a.length()).mapNotNull { GroupOp.fromJson(a.getJSONObject(it)) }.forEach { doc.apply(it) } }
                println("LIVE edit from them=${edit?.body?.optJSONArray("ops")?.length()} ops; list now: ${doc.items().map { it.track.optString("title") }} title=${doc.title}")
            }
        }
        delay(2000)
        messenger.close(); scope.cancel()
    }
}
