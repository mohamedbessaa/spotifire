package fr.spotifeur.social

import fr.spotifeur.data.Music
import fr.spotifeur.data.MusicKind
import org.json.JSONArray
import org.json.JSONObject

/** A received item: what to show and open, and the songs that came with it (playlists). */
data class SharedMusic(val item: Music, val tracks: List<Music>)

/**
 * What a share carries. Songs, albums and artists travel as their Spotify or YouTube Music id with what the inbox
 * shows. A playlist also carries its songs, so a private or here-only playlist (mixed sources, liked songs) plays and
 * saves on the friend's phone; files on this device never leave it.
 */
object SharedItems {
    private val TRACK_ID = Regex("""spotify:track:[A-Za-z0-9]{22}|ytm:track:[A-Za-z0-9_-]{11}""")
    private val COLLECTION_ID = Regex("""spotify:(album|artist|playlist):[A-Za-z0-9]{22}|ytm:playlist:[A-Za-z0-9_-]{2,64}""")

    fun encode(item: Music, tracks: List<Music>): JSONObject {
        val json = JSONObject().put("kind", kindName(item.kind)).put("title", item.title.take(200)).put("subtitle", item.subtitle.take(200))
        item.imageUrl?.takeIf { it.startsWith("https://") }?.let { json.put("image", it) }
        if (item.id.matches(TRACK_ID) || item.id.matches(COLLECTION_ID)) json.put("id", item.id)
        if (item.kind == MusicKind.Track) {
            json.put("durationMs", item.durationMs).put("album", item.album.take(200))
            item.isrc?.let { json.put("isrc", it) }
        }
        if (item.kind == MusicKind.Playlist) json.put("tracks", JSONArray(tracks.filter { it.id.matches(TRACK_ID) }.map(::track)))
        return json
    }

    fun decode(json: JSONObject): SharedMusic? {
        val kind = when (json.optString("kind")) {
            "song" -> MusicKind.Track; "album" -> MusicKind.Album; "artist" -> MusicKind.Artist; "playlist" -> MusicKind.Playlist
            else -> return null
        }
        val title = json.optString("title").take(200).takeIf(String::isNotBlank) ?: return null
        val tracks = json.optJSONArray("tracks")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::fromTrack) } }.orEmpty()
        val id = json.optString("id").takeIf { it.matches(TRACK_ID) || it.matches(COLLECTION_ID) }
            ?: if (kind == MusicKind.Playlist && tracks.isNotEmpty()) "friend:" + java.util.UUID.randomUUID() else return null
        val item = Music(id, title, json.optString("subtitle").take(200), kind,
            imageUrl = json.optString("image").takeIf { it.startsWith("https://") },
            durationMs = json.optInt("durationMs"), album = json.optString("album").take(200),
            isrc = json.optString("isrc").takeIf { it.matches(Regex("[A-Z0-9]{12}")) })
        return SharedMusic(item, tracks)
    }

    private fun kindName(kind: MusicKind) = when (kind) {
        MusicKind.Track -> "song"; MusicKind.Album -> "album"; MusicKind.Artist -> "artist"; else -> "playlist"
    }

    /** One song as shared playlists, Blends and Jam carry it. */
    fun track(t: Music) = JSONObject().put("id", t.id).put("title", t.title.take(200)).put("subtitle", t.subtitle.take(200))
        .put("durationMs", t.durationMs).apply {
            if (t.album.isNotEmpty()) put("album", t.album.take(200))
            t.imageUrl?.takeIf { it.startsWith("https://") }?.let { put("image", it) }
            t.isrc?.let { put("isrc", it) }
        }

    fun fromTrack(json: JSONObject): Music? {
        val id = json.optString("id").takeIf { it.matches(TRACK_ID) } ?: return null
        return Music(id, json.optString("title").take(200), json.optString("subtitle").take(200), MusicKind.Track,
            imageUrl = json.optString("image").takeIf { it.startsWith("https://") }, durationMs = json.optInt("durationMs"),
            album = json.optString("album").take(200), isrc = json.optString("isrc").takeIf { it.matches(Regex("[A-Z0-9]{12}")) })
    }
}
