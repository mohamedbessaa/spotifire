package fr.spotifeur.social

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip13Pow.miner.PoWMiner
import com.vitorpamplona.quartz.nip13Pow.pow
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetch
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
import org.json.JSONObject
import java.util.UUID

/** The relays measured on 2026-11-25 to deliver gift wraps, answer tag lookups and forward ephemeral events. */
object SocialRelays {
    val default: Set<NormalizedRelayUrl> = listOf(
        "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social", "wss://offchain.pub",
    ).map(RelayUrlNormalizer::normalize).toSet()
}

/**
 * Private messages between Spotifire friends. Each message is JSON, split by [Envelope] when large, and every part is
 * a rumor sealed by its author and gift-wrapped to each recipient (NIP-17/44/59): relays see neither the content nor
 * who sent it. A rumor is accepted only when it names the same author as the seal that carries it.
 */
class Messenger(
    private val keys: SocialKeys,
    sockets: WebsocketBuilder,
    scope: CoroutineScope,
    private val relays: Set<NormalizedRelayUrl> = SocialRelays.default,
) : AutoCloseable {
    data class Incoming(val from: String, val body: JSONObject, val pow: Int, val sentAt: Long)

    private val client = NostrClient(sockets, scope)
    private val assembler = Envelope.Assembler()
    private val seenWraps = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 5000
    }
    // A queue, not a live stream: a message that arrives before anyone listens waits instead of being lost.
    private val queue = kotlinx.coroutines.channels.Channel<Incoming>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val incoming: kotlinx.coroutines.flow.Flow<Incoming> = queue.receiveAsFlow()

    /** Connects and listens for wraps addressed to us since [since] (seconds). Wraps are backdated up to two days. */
    fun start(since: Long) {
        client.connect()
        val filter = Filter(kinds = listOf(GiftWrapEvent.KIND), tags = mapOf("p" to listOf(keys.publicKeyHex)),
            since = (since - TWO_DAYS).coerceAtLeast(0))
        client.subscribe(INBOX, relays.associateWith { listOf(filter) }, object : SubscriptionListener {
            override suspend fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                if (event is GiftWrapEvent) receive(event)
            }
        })
    }

    private suspend fun receive(wrap: GiftWrapEvent) {
        synchronized(seenWraps) { if (seenWraps.put(wrap.id, true) != null) return }
        val seal = runCatching { wrap.unwrapThrowing(keys.signer) as? SealEvent }.getOrNull() ?: return
        val rumor = runCatching { seal.unsealThrowing(keys.signer) }.getOrNull() ?: return
        // Quartz already makes the rumor's author the seal's signer; checked again here so a library change cannot
        // let a forged author through.
        if (rumor.pubKey != seal.pubKey || rumor.kind != RUMOR_KIND) return
        val body = assembler.accept(rumor.content) ?: return
        queue.send(Incoming(seal.pubKey, body, wrap.pow(), rumor.createdAt))
    }

    /**
     * Sends [body] to each of [to]; true when every part reached at least one relay. [pow] mines the wraps (NIP-13), as
     * friend requests must. The same [id] sent twice arrives once.
     */
    suspend fun send(to: Collection<String>, body: JSONObject, pow: Int = 0, id: String = UUID.randomUUID().toString(),
                     /** NIP-40: relays drop the wraps after this time (seconds). */ expiresAt: Long? = null): Boolean =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis() / 1000
            var accepted = 0; var refused = 0
            Envelope.encode(id, body).forEach { part ->
                val rumor = keys.signer.sign<Event>(now, RUMOR_KIND, arrayOf(arrayOf("t", "spotifire")), part)
                to.forEach { recipient ->
                    val seal = SealEvent.create(rumor, recipient, keys.signer)
                    val wrap = GiftWrapEvent.create(seal, recipient, templateConversion = { template, ephemeral ->
                        val expiring = if (expiresAt == null) template
                            else com.vitorpamplona.quartz.nip01Core.signers.EventTemplate(template.createdAt, template.kind,
                                template.tags + arrayOf(arrayOf("expiration", expiresAt.toString())), template.content)
                        if (pow > 0) PoWMiner.run(expiring, ephemeral, pow) else expiring
                    })
                    if (client.publishAndConfirm(wrap, relays, timeoutInSeconds = 10, untilFirstAccept = true)) accepted++ else refused++
                }
            }
            if (refused > 0) android.util.Log.w("SpotifeurSocial", "message $id: $refused of ${accepted + refused} parts reached no relay")
            refused == 0
        }

    /** Publishes a public signed event (the directory entry); true when a relay accepted it. */
    suspend fun publishPublic(event: Event): Boolean = client.publishAndConfirm(event, relays, timeoutInSeconds = 10)

    /** Stored events matching [filters] from every relay, deduplicated; relays that do not answer in time are skipped. */
    suspend fun query(filters: List<Filter>, timeoutMs: Long = 6_000): List<Event> = kotlinx.coroutines.coroutineScope {
        val found = java.util.concurrent.ConcurrentHashMap<String, Event>()
        relays.map { relay ->
            async { kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { client.fetch(relay, filters) { found[it.id] = it } } }
        }.awaitAll()
        found.values.toList()
    }

    // Group path: shared playlists and Jam ----------------------------------------------------------------------

    /** A group message: its group, its proven author (the inner signature), and whether it was live (not stored). */
    data class GroupIncoming(val group: SharedGroup, val author: String, val body: JSONObject, val live: Boolean)

    private val groupQueue = kotlinx.coroutines.channels.Channel<GroupIncoming>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val groupIncoming: kotlinx.coroutines.flow.Flow<GroupIncoming> = groupQueue.receiveAsFlow()
    @Volatile private var watched: Map<String, SharedGroup> = emptyMap()
    private val groupAssembler = Envelope.Assembler()

    /**
     * Listens for the events of [groups] (every key epoch passed is read). Events carry only the group's tag and are
     * signed by a throwaway key; inside the group-key encryption is the author's own signed event.
     */
    fun watchGroups(groups: Collection<SharedGroup>, since: Long) {
        watched = groups.associateBy { it.tag }
        if (watched.isEmpty()) { client.unsubscribe(GROUPS); return }
        val filter = Filter(kinds = listOf(GROUP_KIND, GROUP_LIVE_KIND), tags = mapOf("h" to watched.keys.toList()), since = since.coerceAtLeast(0))
        client.subscribe(GROUPS, relays.associateWith { listOf(filter) }, object : SubscriptionListener {
            override suspend fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) = receiveGroup(event)
        })
    }

    private suspend fun receiveGroup(event: Event) {
        if (event.kind != GROUP_KIND && event.kind != GROUP_LIVE_KIND) return
        synchronized(seenWraps) { if (seenWraps.put(event.id, true) != null) return }
        val tag = event.tags.firstOrNull { it.size > 1 && it[0] == "h" }?.get(1) ?: return
        val group = watched[tag] ?: return
        val inner = runCatching { Event.fromJson(group.open(event.content)?.getString("e") ?: return) }.getOrNull() ?: return
        if (inner.kind != GROUP_INNER_KIND || !inner.verify()) return
        if (inner.pubKey !in group.members) return
        val body = groupAssembler.accept(inner.content) ?: return
        groupQueue.send(GroupIncoming(group, inner.pubKey, body, event.kind == GROUP_LIVE_KIND))
    }

    /** Posts [body] to [group]; live events (Jam) are not stored by relays and are not waited on. */
    suspend fun publishGroup(group: SharedGroup, body: JSONObject, live: Boolean = false, id: String = UUID.randomUUID().toString()): Boolean =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis() / 1000
            var ok = true
            Envelope.encode(id, body).forEach { part ->
                val inner = keys.signer.sign<Event>(now, GROUP_INNER_KIND, emptyArray(), part)
                val content = group.seal(JSONObject().put("e", inner.toJson()))
                val outer = com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal(com.vitorpamplona.quartz.nip01Core.crypto.KeyPair())
                    .sign<Event>(now, if (live) GROUP_LIVE_KIND else GROUP_KIND, arrayOf(arrayOf("h", group.tag)), content)
                if (live) client.publish(outer, relays)
                else if (!client.publishAndConfirm(outer, relays, timeoutInSeconds = 10, untilFirstAccept = true)) ok = false
            }
            ok
        }

    override fun close() = client.close()

    companion object {
        /** The kind of Spotifire's rumors: never published on its own, only inside seals. */
        const val RUMOR_KIND = 7441
        /** Shared playlist edits and snapshots (stored), Jam state (ephemeral), and the author-signed event inside. */
        const val GROUP_KIND = 7442
        const val GROUP_LIVE_KIND = 27442
        const val GROUP_INNER_KIND = 7443
        private const val GROUPS = "spotifire-groups"
        private const val INBOX = "spotifire-inbox"
        private const val TWO_DAYS = 2L * 24 * 3600
    }
}
