package fr.spotifeur.presentation.utils

import fr.spotifeur.data.Music

/** Public link of a Spotify entity; null for local collections such as the liked songs. */
fun Music.spotifyShareUrl(): String? {
    if (id.startsWith(fr.spotifeur.data.youtube.YTM_TRACK_PREFIX)) return "https://music.youtube.com/watch?v=" + id.removePrefix(fr.spotifeur.data.youtube.YTM_TRACK_PREFIX)
    if (id.startsWith(fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX)) return "https://music.youtube.com/playlist?list=" + id.removePrefix(fr.spotifeur.data.youtube.YTM_PLAYLIST_PREFIX)
    val parts = id.split(':')
    if (parts.size != 3 || parts[0] != "spotify") return null
    return when (parts[1]) {
        "track", "album", "artist", "playlist" -> "https://open.spotify.com/${parts[1]}/${parts[2]}"
        else -> null
    }
}
