package fr.spotifeur.data

import org.json.JSONObject

/** The signed-in account's public profile: its display name and picture, as Spotify's own clients show them. */
data class SpotifyProfile(val name: String, val imageUrl: String?)

/**
 * `user-profile-view/v3/profile/{username}` on spclient, the endpoint the official clients read the profile header
 * from. The username of an account made with an email is a random id ("31ab…"): never show it as a name.
 */
object SpotifyProfiles {
    @Volatile private var cached: Pair<String, SpotifyProfile>? = null

    suspend fun current(): SpotifyProfile? {
        val session = SpotifyAccount.current() ?: return null
        val username = runCatching { session.username() }.getOrNull() ?: return null
        cached?.let { (who, profile) -> if (who == username) return profile }
        val url = spotifyUrl("user-profile-view/v3/profile/$username").newBuilder()
            .addQueryParameter("playlist_limit", "0").addQueryParameter("artist_limit", "0")
            .addQueryParameter("episode_limit", "0").addQueryParameter("market", "from_token").build()
        val profile = runCatching {
            parse(authenticatedSpotifyRequest(SpotifyAccount.transport, session, url, "Profile", contentType = "application/json").decodeToString())
        }.onFailure { android.util.Log.i("SpotifeurProfile", "profile unavailable: ${it.javaClass.simpleName}") }.getOrNull() ?: return null
        cached = username to profile
        return profile
    }

    internal fun parse(json: String): SpotifyProfile? {
        val root = JSONObject(json)
        val name = root.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        val image = root.optString("image_url").trim().takeIf { it.startsWith("https://") }
        return SpotifyProfile(name, image)
    }
}
