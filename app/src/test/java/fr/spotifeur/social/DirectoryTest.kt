package fr.spotifeur.social

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryTest {
    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val url = setOf(RelayUrlNormalizer.normalize("wss://fake.relay"))
    @After fun tearDown() { scope.cancel(); relay.close() }

    private fun messenger(keys: SocialKeys) = Messenger(keys, relay, scope, url).also { it.start(since = 0) }

    @Test
    fun aDiscoverableNameIsFoundExactlyAndIgnoringCase() = runBlocking {
        val sara = SocialKeys.create()
        Directory(messenger(sara)).publish(sara, PublicProfile("Sara", null), discoverable = true)
        val me = Directory(messenger(SocialKeys.create()))
        val found = me.lookup("sara")
        assertEquals(listOf(sara.publicKeyHex), found.map { it.pubKey })
        assertEquals("Sara", found.single().name)
        assertTrue("no partial match by tag filter", me.lookup("sar").isEmpty())
    }

    @Test
    fun aHandlePicksOneOfTwoSarasAndBothAreShownOtherwise() = runBlocking {
        val one = SocialKeys.create(); val two = SocialKeys.create()
        Directory(messenger(one)).publish(one, PublicProfile("Sara", null), true)
        Directory(messenger(two)).publish(two, PublicProfile("Sara", null), true)
        val me = Directory(messenger(SocialKeys.create()))
        assertEquals(2, me.lookup("Sara").size)
        assertEquals(listOf(two.publicKeyHex), me.lookup(Handles.handle("Sara", two.publicKeyHex)).map { it.pubKey })
    }

    @Test
    fun turningDiscoveryOffHidesTheName() = runBlocking {
        val sara = SocialKeys.create()
        val directory = Directory(messenger(sara))
        directory.publish(sara, PublicProfile("Sara", null), true)
        directory.publish(sara, PublicProfile("Sara", null), false)
        assertTrue(Directory(messenger(SocialKeys.create())).lookup("Sara").isEmpty())
    }

    @Test
    fun aNameThatIsAlsoAFriendsNameIsFlagged() {
        val friend = Friend("f".repeat(64), "Sara", 0)
        val stranger = DirectoryEntry("e".repeat(64), "Sara", null)
        assertTrue(Directory.looksLikeFriend(stranger, listOf(friend)))
        assertTrue(!Directory.looksLikeFriend(DirectoryEntry(friend.pubKey, "Sara", null), listOf(friend)))
    }
}
