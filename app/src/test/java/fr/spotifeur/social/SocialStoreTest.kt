package fr.spotifeur.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialStoreTest {
    private val me = "a".repeat(64)
    private val sara = "b".repeat(64)
    private val stranger = "c".repeat(64)
    private fun store() = SocialState(me)
    private fun msg(from: String, body: JSONObject, pow: Int = 0, at: Long = 1000) = Messenger.Incoming(from, body, pow, at)
    private fun share(id: String) = JSONObject().put("type", "share").put("id", id)
        .put("item", JSONObject().put("kind", "playlist").put("id", "spotify:playlist:x").put("title", "Night Drive"))

    @Test
    fun aRequestNeedsProofOfWorkAndWaitsForAnAnswer() {
        val s = store()
        s.handle(msg(sara, JSONObject().put("type", "friend-request").put("name", "Sara"), pow = 4))
        assertTrue("too little work is refused", s.requests.isEmpty())
        s.handle(msg(sara, JSONObject().put("type", "friend-request").put("name", "Sara"), pow = 16))
        assertEquals(listOf(sara), s.requests.map { it.pubKey })
        assertTrue("not a friend until accepted", s.friends.isEmpty())
        s.accept(sara)
        assertEquals(listOf(sara), s.friends.map { it.pubKey })
        assertTrue(s.requests.isEmpty())
    }

    @Test
    fun anAcceptOnlyCountsForARequestWeSent() {
        val s = store()
        s.handle(msg(stranger, JSONObject().put("type", "friend-accept").put("name", "X")))
        assertTrue(s.friends.isEmpty())
        s.requested(sara, "Sara")
        s.handle(msg(sara, JSONObject().put("type", "friend-accept").put("name", "Sara")))
        assertEquals(listOf(sara), s.friends.map { it.pubKey })
    }

    @Test
    fun aShareFromSomeoneWeAskedProvesTheyAccepted() {
        val s = store()
        s.requested(sara, "Sara")
        // Their "accept" was lost; their first share still makes the friendship whole on our side.
        assertTrue(s.handle(msg(sara, share("7"))))
        assertEquals(listOf(sara), s.friends.map { it.pubKey })
        assertEquals(listOf("7"), s.inbox.map { it.id })
        assertTrue(s.pending.isEmpty())
    }

    @Test
    fun sharesFromStrangersOrBlockedFriendsAreDropped() {
        val s = store()
        s.handle(msg(stranger, share("1")))
        assertTrue(s.inbox.isEmpty())
        s.requested(sara, "Sara"); s.handle(msg(sara, JSONObject().put("type", "friend-accept").put("name", "Sara")))
        s.handle(msg(sara, share("2")))
        s.handle(msg(sara, share("2")))
        assertEquals("a repeat counts once", listOf("2"), s.inbox.map { it.id })
        s.block(sara)
        s.handle(msg(sara, share("3")))
        assertEquals(listOf("2"), s.inbox.map { it.id })
        assertTrue("blocking removes the friendship", s.friends.isEmpty())
    }

    @Test
    fun receiptsUpdateWhatWeSent() {
        val s = store()
        s.requested(sara, "Sara"); s.handle(msg(sara, JSONObject().put("type", "friend-accept").put("name", "Sara")))
        s.sent("9", listOf(sara), JSONObject().put("kind", "song").put("title", "Starboy"), at = 50)
        s.handle(msg(sara, JSONObject().put("type", "receipt").put("of", "9").put("state", "seen")))
        assertEquals(InboxState.SEEN, s.outbox.single().states[sara])
        s.handle(msg(sara, JSONObject().put("type", "receipt").put("of", "9").put("state", "saved")))
        assertEquals(InboxState.SAVED, s.outbox.single().states[sara])
        // A late "seen" never takes back "saved".
        s.handle(msg(sara, JSONObject().put("type", "receipt").put("of", "9").put("state", "seen")))
        assertEquals(InboxState.SAVED, s.outbox.single().states[sara])
    }

    @Test
    fun theStateSurvivesSavingAndABackupRestoresFriendsAndBlocks() {
        val s = store()
        s.requested(sara, "Sara"); s.handle(msg(sara, JSONObject().put("type", "friend-accept").put("name", "Sara")))
        s.block(stranger)
        s.handle(msg(sara, share("2")))
        val reloaded = SocialState.fromJson(me, s.toJson())
        assertEquals(s.friends, reloaded.friends)
        assertEquals(s.inbox, reloaded.inbox)
        assertEquals(setOf(stranger), reloaded.blocked)
        // A new phone: empty state, then the backup we sent to ourselves.
        val fresh = store()
        fresh.handle(msg(me, s.backup().put("at", 500)))
        assertEquals(s.friends.map { it.pubKey }, fresh.friends.map { it.pubKey })
        assertEquals(setOf(stranger), fresh.blocked)
        // An older backup never overwrites a newer one.
        fresh.handle(msg(me, store().backup().put("at", 100)))
        assertEquals(1, fresh.friends.size)
    }

    @Test
    fun aFriendsProfileUpdatesTheirNameAndPhotoNewestFirst() {
        val s = store()
        val photo = JSONObject().put("urls", org.json.JSONArray(listOf("https://nostr.download/abc"))).put("key", "k".repeat(64)).put("sha256", "s".repeat(64))
        s.handle(msg(stranger, JSONObject().put("type", "profile").put("name", "Spam").put("at", 5)))
        assertTrue("strangers have no profile here", s.profiles.isEmpty())
        s.requested(sara, "Sara"); s.handle(msg(sara, JSONObject().put("type", "friend-accept").put("name", "Sara")))
        s.handle(msg(sara, JSONObject().put("type", "profile").put("name", "Sara K").put("bio", "Synths").put("photo", photo).put("at", 10)))
        assertEquals("Sara K", s.friends.single().name)
        assertEquals("Synths", s.profiles[sara]!!.bio)
        assertEquals("s".repeat(64), s.profiles[sara]!!.photo!!.sha256)
        s.handle(msg(sara, JSONObject().put("type", "profile").put("name", "Old").put("at", 9)))
        assertEquals("an older profile never wins", "Sara K", s.friends.single().name)
        s.handle(msg(sara, JSONObject().put("type", "profile").put("name", "Sara K").put("at", 11)))
        assertEquals("photo removed", null, s.profiles[sara]!!.photo)
        assertEquals(s.profiles, SocialState.fromJson(me, s.toJson()).profiles)
    }

    @Test
    fun listeningNowComesOnlyFromFriendsAndExpires() {
        val s = store()
        val track = JSONObject().put("id", "spotify:track:4cOdK2wGLETKBW3PvgPWqT").put("title", "Song").put("subtitle", "Artist")
        s.handle(msg(stranger, JSONObject().put("type", "listening").put("track", track).put("at", 100).put("until", 4000)))
        assertTrue(s.listeningAt(200).isEmpty())
        s.requested(sara, "Sara"); s.handle(msg(sara, JSONObject().put("type", "friend-accept").put("name", "Sara")))
        s.handle(msg(sara, JSONObject().put("type", "listening").put("track", track).put("at", 100).put("until", 3700)))
        assertEquals("Song", s.listeningAt(200)[sara]!!.title)
        assertTrue("an hour later it's gone", s.listeningAt(3701).isEmpty())
        s.handle(msg(sara, JSONObject().put("type", "listening").put("at", 150).put("until", 3750)))
        assertTrue("stopped", s.listeningAt(200).isEmpty())
        s.handle(msg(sara, JSONObject().put("type", "listening").put("track", track).put("at", 120).put("until", 3720)))
        assertTrue("an older update never comes back", s.listeningAt(200).isEmpty())
    }
}
