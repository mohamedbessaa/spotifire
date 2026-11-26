package fr.spotifeur.social

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Everything the Friends screens show, in one value. */
data class SocialSnapshot(
    val me: String? = null,
    val profile: MyProfile = MyProfile(),
    val friends: List<Friend> = emptyList(),
    val requests: List<FriendRequest> = emptyList(),
    val pending: Map<String, String> = emptyMap(),
    val blocked: Set<String> = emptySet(),
    val inbox: List<InboxItem> = emptyList(),
    val outbox: List<OutboxItem> = emptyList(),
    val profiles: Map<String, FriendProfile> = emptyMap(),
    /** Friends' photos, decrypted on this phone: public key to file path. */
    val photos: Map<String, String> = emptyMap(),
    /** What friends are playing right now (only those who share it). */
    val listening: Map<String, Listening> = emptyMap(),
    val connected: Boolean = false,
) {
    val unread: Int get() = requests.size + inbox.count { it.state == InboxState.NEW }
}

/** The user's own profile as they set it in the app. */
data class MyProfile(val name: String = "", val bio: String = "", val discoverable: Boolean = true, val photo: String? = null)

/**
 * The Friends side of the app: identity, the relay connection, the rules of [SocialState], storage on the phone,
 * receipts, re-sends and notifications. One instance per process.
 */
object Social {
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var state: SocialState? = null
    private var messenger: Messenger? = null
    private var running: Job? = null
    private val mutable = MutableStateFlow(SocialSnapshot())
    val snapshot: StateFlow<SocialSnapshot> = mutable.asStateFlow()
    /** Asks the UI to show the Friends tab (a notification was tapped). */
    val openFriends = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    private val http by lazy { OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build() }

    fun init(context: Context) { if (app == null) app = context.applicationContext }

    /** Work that must finish even when the screen that asked for it is gone. */
    fun launch(block: suspend () -> Unit) { scope.launch { block() } }
    internal fun launchJob(block: suspend () -> Unit): Job = scope.launch { block() }
    internal fun messengerOrNull() = messenger
    /** Listens to the shared playlists' and the Jam's groups together (one subscription). */
    internal fun rewatchGroups() {
        messenger?.watchGroups(SharedPlaylists.watchedGroups() + Jam.watched(), since = System.currentTimeMillis() / 1000 - 30L * 24 * 3600)
    }
    internal suspend fun deliverTo(to: List<String>, body: JSONObject) = deliver(to, body)
    internal fun nameOf(key: String) = state?.friends?.firstOrNull { it.pubKey == key }?.name ?: "A friend"

    /** Screens showing Friends count as use: a battery-mode sync never cuts the connection under them. */
    @Volatile private var visible = 0
    fun inUse() = visible > 0
    fun screenShown() { visible++; start() }
    fun screenHidden() { visible = (visible - 1).coerceAtLeast(0) }

    private fun dir(context: Context) = File(context.filesDir, "social").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences("social", Context.MODE_PRIVATE)

    /** Connects to the relays and starts receiving; safe to call again. */
    fun start() {
        val context = app ?: return
        if (running?.isActive == true) return
        running = scope.launch {
            val keys = Identity.current(context)
            lock.withLock {
                if (state == null) state = load(context, keys.publicKeyHex)
                publish()
            }
            val m = Messenger(keys, BasicOkHttpWebSocket.Builder { http }, scope).also { messenger = it }
            m.start(since = prefs(context).getLong(LAST_SYNC, 0))
            mutable.value = mutable.value.copy(connected = true)
            launch { resendLoop() }
            launch { retryLoop() }
            launch { SharedPlaylists.startWatching(context) }
            launch { while (isActive) { runCatching { Blends.start(context) }; delay(6 * 3600 * 1000L) } }
            launch { m.groupIncoming.collect { runCatching { SharedPlaylists.onGroup(context, it) } } }
            // Presence expires on its own; the screens follow.
            launch { while (isActive) { delay(60_000); lock.withLock { publish() } } }
            launch { publishDirectory(); publishInboxRelays(keys); cachePhotos() }
            m.incoming.collect { incoming ->
                val type = incoming.body.optString("type")
                if (type.startsWith("playlist-") || type.startsWith("jam-") || type.startsWith("blend-")) {
                    val friend = lock.withLock { state!!.isFriend(incoming.from) && incoming.from !in state!!.blocked }
                    runCatching {
                        when {
                            type.startsWith("playlist-") -> SharedPlaylists.onDirect(context, incoming, friend)
                            type.startsWith("jam-") -> Jam.onDirect(context, incoming, friend)
                            else -> Blends.onDirect(context, incoming, friend)
                        }
                    }
                    return@collect
                }
                // One bad message (or a failing notification) must never end the receiving loop.
                runCatching {
                    val accepted = lock.withLock { state!!.handle(incoming).also { if (it) save(context) } }
                    prefs(context).edit().putLong(LAST_SYNC, maxOf(prefs(context).getLong(LAST_SYNC, 0), incoming.sentAt)).apply()
                    if (accepted) { publish(); onAccepted(context, incoming) }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; android.util.Log.w("SpotifeurSocial", "message skipped: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Switches to an identity brought back with its recovery words; its friends return with its self-backup. */
    suspend fun restore(keys: SocialKeys) {
        val context = app ?: return
        stop()
        Identity.replace(context, keys)
        lock.withLock {
            java.io.File(dir(context), "state.json").delete()
            prefs(context).edit().remove(LAST_SYNC).remove(INBOX_RELAYS_SENT).apply()
            state = null
        }
        mutable.value = SocialSnapshot(profile = mutable.value.profile)
        start()
    }

    fun stop() {
        running?.cancel(); running = null
        messenger?.close(); messenger = null
        mutable.value = mutable.value.copy(connected = false)
    }

    private suspend fun onAccepted(context: Context, incoming: Messenger.Incoming) {
        when (incoming.body.optString("type")) {
            "friend-request" -> SocialNotifier.request(context, incoming.body.optString("name"))
            "friend-accept" -> { SocialNotifier.accepted(context, incoming.body.optString("name")); sendBackup(); sendProfile(listOf(incoming.from)) }
            "profile" -> cachePhotos()
            "share" -> {
                val name = state?.friends?.firstOrNull { it.pubKey == incoming.from }?.name.orEmpty()
                SocialNotifier.share(context, incoming.body.optString("id"), name, incoming.body.optJSONObject("item"), incoming.body.optString("note"))
            }
        }
    }

    // Actions ---------------------------------------------------------------------------------------------------

    suspend fun sendRequest(pubKey: String, name: String, note: String = "") {
        if (state == null) return
        val me = mutable.value.profile
        lock.withLock { state!!.requested(pubKey, name); save(app!!) }
        publish()
        deliver(listOf(pubKey), JSONObject().put("type", "friend-request").put("name", me.name).put("note", note), pow = SocialState.REQUEST_POW)
    }

    suspend fun accept(pubKey: String) {
        lock.withLock { state!!.accept(pubKey); save(app!!) }
        publish()
        deliver(listOf(pubKey), JSONObject().put("type", "friend-accept").put("name", mutable.value.profile.name))
        sendProfile(listOf(pubKey))
        sendBackup()
    }

    suspend fun decline(pubKey: String) = change { decline(pubKey) }
    suspend fun block(pubKey: String) { change { block(pubKey) }; sendBackup() }
    suspend fun unblock(pubKey: String) { change { unblock(pubKey) }; sendBackup() }
    suspend fun remove(pubKey: String) {
        change { remove(pubKey) }
        deliver(listOf(pubKey), JSONObject().put("type", "unfriend"))
        sendBackup()
    }

    /** Sends [item] (a song, album, artist or playlist as [SharedItems] encodes it) to [to] with an optional note. */
    suspend fun share(to: List<String>, item: JSONObject, note: String) {
        if (state == null) return
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000
        lock.withLock { state!!.sent(id, to, item, now); save(app!!) }
        publish()
        deliver(to, JSONObject().put("type", "share").put("id", id).put("item", item).put("note", note.take(140)), id = id)
    }

    /** Marks an inbox item and tells its sender (Seen, Saved or Declined). */
    suspend fun mark(id: String, newState: InboxState) {
        val item = lock.withLock {
            state!!.inbox.firstOrNull { it.id == id }?.takeIf { newState.ordinal > it.state.ordinal }?.also { state!!.setInbox(id, newState); save(app!!) }
        } ?: return
        publish()
        deliver(listOf(item.from), JSONObject().put("type", "receipt").put("of", id).put("state", newState.name.lowercase()))
    }

    suspend fun updateProfile(profile: MyProfile) {
        val context = app ?: return
        val before = mutable.value.profile
        val clean = profile.copy(name = profile.name.trim().take(Directory.MAX_NAME), bio = profile.bio.trim().take(160), photo = myPhoto(context))
        prefs(context).edit().putString(PROFILE, JSONObject().put("name", clean.name).put("bio", clean.bio)
            .put("discoverable", clean.discoverable).toString()).apply()
        mutable.value = mutable.value.copy(profile = clean)
        if (state == null) return
        if (before.discoverable != clean.discoverable) { if (clean.discoverable) uploadThumbnail() else removeThumbnail() }
        publishDirectory()
        if (before.name != clean.name || before.bio != clean.bio) sendProfile()
    }

    // Profile photo ---------------------------------------------------------------------------------------------

    /** Our photo on the phone; its name changes with it, so images cached under the old name are not shown again. */
    private fun myPhoto(context: Context) = dir(context).listFiles()?.firstOrNull { it.name.startsWith("me-") && it.name.endsWith(".jpg") }?.path
    private fun clearMyPhoto(context: Context) = dir(context).listFiles()?.filter { it.name.startsWith("me-") && it.name.endsWith(".jpg") }?.forEach { it.delete() }

    /**
     * Sets the profile photo: friends get it encrypted (its key only travels in the profile message); the small public
     * thumbnail exists only while the name is discoverable.
     */
    suspend fun setPhoto(photo: ProfileImages.Prepared) {
        val context = app ?: return
        val keys = Identity.current(context)
        val sealed = PhotoCrypto.seal(photo.large)
        val urls = Blossom.upload(sealed.bytes, "image/jpeg", keys)
        if (urls.isEmpty()) throw java.io.IOException("Couldn't upload the photo. Check your connection and try again.")
        val old = prefs(context).getString(PHOTO_REF, null)?.let { PhotoRef.from(runCatching { JSONObject(it) }.getOrNull()) }
        clearMyPhoto(context)
        File(dir(context), "me-" + sealed.sha256.take(12) + ".jpg").writeBytes(photo.large)
        File(dir(context), "me_small.jpg").writeBytes(photo.small)
        prefs(context).edit().putString(PHOTO_REF, PhotoRef(urls, sealed.keyHex, sealed.sha256).toJson().toString()).apply()
        mutable.value = mutable.value.copy(profile = mutable.value.profile.copy(photo = myPhoto(context)))
        if (mutable.value.profile.discoverable) uploadThumbnail()
        publishDirectory()
        sendProfile()
        old?.let { Blossom.delete(it.sha256, keys) }
    }

    suspend fun removePhoto() {
        val context = app ?: return
        val keys = Identity.current(context)
        val old = prefs(context).getString(PHOTO_REF, null)?.let { PhotoRef.from(runCatching { JSONObject(it) }.getOrNull()) }
        prefs(context).edit().remove(PHOTO_REF).apply()
        clearMyPhoto(context)
        removeThumbnail()
        File(dir(context), "me_small.jpg").delete()
        mutable.value = mutable.value.copy(profile = mutable.value.profile.copy(photo = null))
        publishDirectory()
        sendProfile()
        old?.let { Blossom.delete(it.sha256, keys) }
    }

    private suspend fun uploadThumbnail() {
        val context = app ?: return
        val small = File(dir(context), "me_small.jpg").takeIf { it.exists() }?.readBytes() ?: return
        val url = Blossom.upload(small, "image/jpeg", Identity.current(context)).firstOrNull() ?: return
        prefs(context).edit().putString(PUBLIC_THUMB, url).putString(PUBLIC_THUMB_SHA, PhotoCrypto.sha256(small)).apply()
    }

    private suspend fun removeThumbnail() {
        val context = app ?: return
        val sha = prefs(context).getString(PUBLIC_THUMB_SHA, null)
        prefs(context).edit().remove(PUBLIC_THUMB).remove(PUBLIC_THUMB_SHA).apply()
        sha?.let { Blossom.delete(it, Identity.current(context)) }
    }

    /** Our name, bio and encrypted photo, to [to] (all friends by default). */
    private suspend fun sendProfile(to: List<String>? = null) {
        val context = app ?: return
        val targets = to ?: state?.friends?.map { it.pubKey }.orEmpty()
        if (targets.isEmpty()) return
        val profile = mutable.value.profile
        val body = JSONObject().put("type", "profile").put("name", profile.name).put("bio", profile.bio).put("at", System.currentTimeMillis() / 1000)
        prefs(context).getString(PHOTO_REF, null)?.let { body.put("photo", JSONObject(it)) }
        deliver(targets, body)
    }

    /** Downloads and opens friends' photos that are not on the phone yet. */
    private suspend fun cachePhotos() {
        val context = app ?: return
        val folder = File(dir(context), "photos").apply { mkdirs() }
        val profiles = lock.withLock { state?.profiles.orEmpty() }
        val found = mutableMapOf<String, String>()
        for ((key, profile) in profiles) {
            val ref = profile.photo ?: continue
            val file = File(folder, ref.sha256 + ".jpg")
            if (!file.exists()) {
                val bytes = Blossom.download(ref.urls, ref.sha256)?.let { PhotoCrypto.open(it, ref.keyHex, ref.sha256) } ?: continue
                file.writeBytes(bytes)
            }
            found[key] = file.path
        }
        // Photos nobody uses any more.
        val used = found.values.toSet()
        folder.listFiles()?.filter { it.path !in used }?.forEach { it.delete() }
        mutable.value = mutable.value.copy(photos = found)
    }

    /** People in the directory with exactly this name or handle; friends' lookalikes are flagged by the caller. */
    suspend fun lookup(query: String): List<DirectoryEntry> {
        val m = messenger ?: return emptyList()
        val me = mutable.value.me
        return Directory(m).lookup(query.trim()).filterNot { it.pubKey == me }
    }

    /** What the QR code on the Friends screen holds: a link to add us, read by Spotifire (see [QrCodes]). */
    fun qrPayload(): String? = mutable.value.me?.let { QrCodes.payload(it, mutable.value.profile.name) }

    /** A scanned or opened code as (public key, name), or null when it is not a Spotifire friend code. */
    fun parseQr(text: String): (Pair<String, String>)? = QrCodes.parse(text)

    /** A friend code opened from outside (camera app, link): the Friends tab shows it, ready to add. */
    val incomingCode = MutableStateFlow<Pair<String, String>?>(null)

    private suspend fun publishDirectory() {
        val m = messenger ?: return
        val context = app ?: return
        val profile = mutable.value.profile
        if (profile.name.isBlank()) return
        val keys = Identity.current(context)
        val thumb = prefs(context).getString(PUBLIC_THUMB, null)
        Directory(m).publish(keys, PublicProfile(profile.name, thumb), profile.discoverable)
    }

    /** NIP-17 inbox relays (kind 10050), so other Nostr clients deliver to the same relays we read. */
    private suspend fun publishInboxRelays(keys: SocialKeys) {
        val m = messenger ?: return
        val context = app ?: return
        if (prefs(context).getBoolean(INBOX_RELAYS_SENT, false)) return
        val tags = SocialRelays.default.map { arrayOf("relay", it.url) }.toTypedArray()
        val event = keys.signer.sign<com.vitorpamplona.quartz.nip01Core.core.Event>(System.currentTimeMillis() / 1000, 10050, tags, "")
        if (m.publishPublic(event)) prefs(context).edit().putBoolean(INBOX_RELAYS_SENT, true).apply()
    }

    private suspend fun change(action: SocialState.() -> Unit) {
        lock.withLock { state!!.action(); save(app!!) }
        publish()
    }

    private suspend fun sendBackup() {
        val me = mutable.value.me ?: return
        val backup = lock.withLock { state?.backup() } ?: return
        deliver(listOf(me), backup, id = "backup")
    }

    /**
     * Sends now, or keeps the message on the phone and tries again (every 10 minutes and at each start, for 30 days)
     * when any part reached no relay: offline, relays down, the app killed mid-send.
     */
    private suspend fun deliver(to: List<String>, body: JSONObject, pow: Int = 0, id: String = java.util.UUID.randomUUID().toString()) {
        val sent = messenger?.let { runCatching { it.send(to, body, pow, id) }.getOrDefault(false) } ?: false
        if (!sent) retries().add(id, to, body, pow, System.currentTimeMillis() / 1000)
    }

    private var retryQueue: RetryQueue? = null
    private fun retries() = retryQueue ?: RetryQueue(File(dir(app!!), "retry.json")).also { retryQueue = it }

    private suspend fun retryLoop() {
        delay(5_000)
        while (scope.isActive) {
            val m = messenger
            if (m != null) {
                retries().drain(System.currentTimeMillis() / 1000) { m.send(it.to, it.body, it.pow, it.id) }
                runCatching { SharedPlaylists.flush(app!!) }
            }
            delay(10 * 60 * 1000L)
        }
    }

    /** Shares not yet seen are sent again every 3 days, for 30 days: relays may have dropped them. */
    private suspend fun resendLoop() {
        while (scope.isActive) {
            val now = System.currentTimeMillis() / 1000
            val due = lock.withLock { state?.outbox.orEmpty() }.filter { o ->
                now - o.at < RESEND_FOR && now - o.lastSent > RESEND_EVERY && o.states.values.any { it == InboxState.NEW }
            }
            due.forEach { o ->
                val unseen = o.states.filterValues { it == InboxState.NEW }.keys.toList()
                messenger?.send(unseen, JSONObject().put("type", "share").put("id", o.id).put("item", o.item), id = o.id + ":" + now)
                lock.withLock { state?.markSent(o.id, now); save(app!!) }
            }
            delay(6 * 3600 * 1000L)
        }
    }

    // Listening now ---------------------------------------------------------------------------------------------

    private var announced: String? = null
    private var presenceJob: Job? = null

    /**
     * Called by the player on every track change and play/pause. With "Share what I'm listening to" on, friends get
     * the track a few seconds after it settles (skipping sends one update), and "stopped" after 5 minutes paused.
     * Files on the phone are never announced.
     */
    fun nowPlaying(id: String?, title: String, subtitle: String, image: String?, playing: Boolean) {
        val context = app ?: return
        if (!SocialMode.enabled(context) || !SocialMode.shareListening(context)) return
        val shareable = id != null && (id.startsWith("spotify:track:") || id.startsWith("ytm:track:"))
        if (playing && shareable && id == announced) { presenceJob?.cancel(); return }
        presenceJob?.cancel()
        presenceJob = scope.launch {
            if (playing && shareable) {
                delay(8_000)
                sendPresence(JSONObject().put("id", id).put("title", title).put("subtitle", subtitle).apply { image?.takeIf { it.startsWith("https://") }?.let { put("image", it) } })
                announced = id
            } else if (announced != null) {
                delay(if (playing) 8_000 else 5 * 60_000L)
                sendPresence(null)
                announced = null
            }
        }
    }

    /** Turning sharing off tells friends right away. */
    fun stopSharingListening() {
        presenceJob?.cancel()
        if (announced != null) scope.launch { sendPresence(null); announced = null }
    }

    private suspend fun sendPresence(track: JSONObject?) {
        val m = messenger ?: return
        val friends = lock.withLock { state?.friends?.map { it.pubKey } }.orEmpty()
        if (friends.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val body = JSONObject().put("type", "listening").put("at", now).put("until", now + 3600).apply { track?.let { put("track", it) } }
        runCatching { m.send(friends, body, expiresAt = now + 3600) }
    }

    // Storage ---------------------------------------------------------------------------------------------------

    private fun load(context: Context, me: String): SocialState {
        val profile = prefs(context).getString(PROFILE, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        mutable.value = mutable.value.copy(me = me, profile = MyProfile(profile?.optString("name").orEmpty(), profile?.optString("bio").orEmpty(),
            profile?.optBoolean("discoverable", true) ?: true, myPhoto(context)))
        val file = File(dir(context), "state.json")
        return runCatching { SocialState.fromJson(me, JSONObject(file.readText())) }.getOrElse { SocialState(me) }
    }

    private fun save(context: Context) {
        val file = File(dir(context), "state.json")
        val tmp = File(file.path + ".tmp")
        tmp.writeText(state!!.toJson().toString())
        tmp.renameTo(file)
    }

    private fun publish() {
        val s = state ?: return
        mutable.value = mutable.value.copy(friends = s.friends, requests = s.requests, pending = s.pending, blocked = s.blocked,
            inbox = s.inbox, outbox = s.outbox, profiles = s.profiles, listening = s.listeningAt(System.currentTimeMillis() / 1000))
    }

    internal const val PUBLIC_THUMB = "public_thumb"
    private const val PUBLIC_THUMB_SHA = "public_thumb_sha"
    private const val PHOTO_REF = "photo_ref"
    private const val INBOX_RELAYS_SENT = "inbox_relays_sent"
    private const val LAST_SYNC = "last_sync"
    private const val PROFILE = "profile"
    private const val RESEND_EVERY = 3L * 24 * 3600
    private const val RESEND_FOR = 30L * 24 * 3600
}
