package fr.spotifeur.social

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import org.json.JSONObject
import java.text.Normalizer

/** What anyone can see of a discoverable user: the name and, optionally, a small public photo. */
data class PublicProfile(val name: String, val thumbnail: String?)

data class DirectoryEntry(val pubKey: String, val name: String, val thumbnail: String?) {
    val handle get() = Handles.handle(name, pubKey)
}

/**
 * The Spotifire user directory: one public, replaceable event per discoverable user (NIP-78 app data, kind 30078),
 * tagged `l=spotifire` and `n=<name in lower case>`, so relays answer an exact-name lookup with a tag filter. Turning
 * discovery off replaces it with an empty entry; friends are never listed.
 */
class Directory(private val messenger: Messenger) {
    suspend fun publish(keys: SocialKeys, profile: PublicProfile, discoverable: Boolean): Boolean {
        val tags = mutableListOf(arrayOf("d", D_TAG), arrayOf("l", LABEL))
        val content = if (discoverable) {
            tags += arrayOf("n", key(profile.name))
            JSONObject().put("name", profile.name.trim()).apply { profile.thumbnail?.let { put("thumb", it) } }.toString()
        } else ""
        val event = keys.signer.sign<Event>(System.currentTimeMillis() / 1000, KIND, tags.toTypedArray(), content)
        return messenger.publishPublic(event)
    }

    /** By exact name (`sara`) or handle (`sara#7Q2K`); several Saras come back sorted by handle. */
    suspend fun lookup(query: String): List<DirectoryEntry> {
        val name = query.substringBefore('#')
        val tag = query.substringAfter('#', "").uppercase().takeIf { it.length == 4 }
        if (key(name).isEmpty()) return emptyList()
        val filter = Filter(kinds = listOf(KIND), tags = mapOf("n" to listOf(key(name)), "l" to listOf(LABEL)), limit = 50)
        return messenger.query(listOf(filter))
            .groupBy { it.pubKey }.mapNotNull { (_, events) -> events.maxBy { it.createdAt }.let(::entry) }
            .filter { key(it.name) == key(name) && (tag == null || Handles.tag(it.pubKey) == tag) }
            .sortedBy { it.handle }
    }

    private fun entry(event: Event): DirectoryEntry? {
        if (!event.tags.any { it.size > 1 && it[0] == "n" }) return null
        val json = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
        val name = json.optString("name").trim().take(MAX_NAME).takeIf(String::isNotEmpty) ?: return null
        val thumb = json.optString("thumb").takeIf { it.startsWith("https://") }
        return DirectoryEntry(event.pubKey, name, thumb)
    }

    companion object {
        const val KIND = 30078
        const val LABEL = "spotifire"
        private const val D_TAG = "spotifire-directory"
        const val MAX_NAME = 30

        /** The lookup form of a name: trimmed, Unicode-normalized, lower case, inner spaces collapsed. */
        fun key(name: String): String =
            Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase().replace(Regex("\\s+"), " ").take(MAX_NAME)

        /** A stranger carrying a friend's name: shown with a warning so a lookalike is not taken for the friend. */
        fun looksLikeFriend(entry: DirectoryEntry, friends: List<Friend>): Boolean =
            friends.any { it.pubKey != entry.pubKey && key(it.name) == key(entry.name) }
    }
}
