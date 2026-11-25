package fr.spotifeur.social

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val url = setOf(RelayUrlNormalizer.normalize("wss://fake.relay"))
    private val alice = SocialKeys.create()
    private val bob = SocialKeys.create()

    @After fun tearDown() { scope.cancel(); relay.close() }

    private fun messenger(keys: SocialKeys) = Messenger(keys, relay, scope, url).also { it.start(since = 0) }

    @Test
    fun aMessageReachesItsRecipientAndNamesItsSender() = runBlocking {
        val a = messenger(alice); val b = messenger(bob)
        a.send(listOf(bob.publicKeyHex), JSONObject().put("type", "share").put("title", "Night Drive"))
        val got = withTimeout(10_000) { b.incoming.first() }
        assertEquals(alice.publicKeyHex, got.from)
        assertEquals("Night Drive", got.body.getString("title"))
    }

    @Test
    fun aTwoThousandSongPlaylistArrivesWhole() = runBlocking {
        val a = messenger(alice); val b = messenger(bob)
        val tracks = JSONArray().apply { repeat(2000) { put(JSONObject().put("id", "spotify:track:$it").put("title", "Song $it with some length").put("isrc", "USRC1%07d".format(it))) } }
        a.send(listOf(bob.publicKeyHex), JSONObject().put("type", "share").put("tracks", tracks))
        val got = withTimeout(20_000) { b.incoming.first() }
        assertEquals(2000, got.body.getJSONArray("tracks").length())
    }

    @Test
    fun aRequestCarriesTheProofOfWorkAskedFor() = runBlocking {
        val a = messenger(alice); val b = messenger(bob)
        a.send(listOf(bob.publicKeyHex), JSONObject().put("type", "friend-request"), pow = 12)
        val got = withTimeout(20_000) { b.incoming.first() }
        assertTrue("pow ${got.pow}", got.pow >= 12)
    }

    @Test
    fun aRumorClaimingSomeoneElseIsAttributedToItsRealSender() = runBlocking {
        val b = messenger(bob)
        val mallory = SocialKeys.create()
        // Mallory seals a rumor that claims to come from Alice.
        val forged = Event("0".repeat(64), alice.publicKeyHex, System.currentTimeMillis() / 1000, Messenger.RUMOR_KIND,
            arrayOf(arrayOf("t", "spotifire")), Envelope.encode("f1", JSONObject().put("type", "share")).single(), "")
        val wrap = GiftWrapEvent.create(SealEvent.create(forged, bob.publicKeyHex, mallory.signer), bob.publicKeyHex)
        val socket = relay.build(url.first(), object : com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener {
            override fun onOpen(pingMillis: Int, compression: Boolean) {}
            override suspend fun onMessage(text: String) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(t: Throwable, code: Int?, response: String?) {}
        })
        socket.connect(); socket.send(JSONArray(listOf("EVENT", JSONObject(wrap.toJson()))).toString())
        // Impersonation is impossible: the message counts as Mallory's (a stranger), never as Alice's.
        val got = withTimeoutOrNull(5_000) { b.incoming.first() }
        if (got != null) assertEquals(mallory.publicKeyHex, got.from)
    }

    @Test
    fun theSameMessageFromSeveralRelaysCountsOnce() = runBlocking {
        val a = messenger(alice); val b = messenger(bob)
        repeat(2) { a.send(listOf(bob.publicKeyHex), JSONObject().put("type", "receipt"), id = "same-id") }
        val got = withTimeoutOrNull(4_000) { b.incoming.take(2).toList() }
        assertNull("a repeat must not arrive twice", got)
    }

    @Test
    fun groupEditsReachMembersWithTheirAuthorAndNobodyElse() = runBlocking {
        val group = SharedGroup.create("p1", "Road trip", alice.publicKeyHex, listOf(alice.publicKeyHex, bob.publicKeyHex))
        val a = messenger(alice); val b = messenger(bob)
        val outsider = messenger(SocialKeys.create())
        b.watchGroups(listOf(group), since = 0)
        outsider.watchGroups(listOf(group.without(bob.publicKeyHex)), since = 0) // a different key: cannot read
        assertTrue(a.publishGroup(group, JSONObject().put("type", "op").put("n", 1)))
        val got = withTimeout(10_000) { b.groupIncoming.first() }
        assertEquals(alice.publicKeyHex, got.author)
        assertEquals("p1", got.group.id)
        assertEquals(1, got.body.getInt("n"))
        assertNull(withTimeoutOrNull(2_000) { outsider.groupIncoming.first() })
    }

    @Test
    fun aBigGroupSnapshotArrivesWholeAndLiveEventsAreNotStored() = runBlocking {
        val group = SharedGroup.create("p2", "Big", alice.publicKeyHex, listOf(alice.publicKeyHex, bob.publicKeyHex))
        val a = messenger(alice); val b = messenger(bob)
        b.watchGroups(listOf(group), since = 0)
        val big = JSONArray().apply { repeat(3000) { put("spotify:track:${"%022d".format(it)}") } }
        a.publishGroup(group, JSONObject().put("type", "snapshot").put("ids", big))
        assertEquals(3000, withTimeout(20_000) { b.groupIncoming.first() }.body.getJSONArray("ids").length())
        a.publishGroup(group, JSONObject().put("type", "jam"), live = true)
        assertEquals("jam", withTimeout(10_000) { b.groupIncoming.first() }.body.getString("type"))
        assertTrue(relay.published.none { it.getInt("kind") == Messenger.GROUP_LIVE_KIND })
    }
}
