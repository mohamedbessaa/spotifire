package fr.spotifeur.social

import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.LOCAL_PLAYLIST_PREFIX
import fr.spotifeur.data.LocalPlaylist
import fr.spotifeur.data.Music
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Connects Friends to the app: shared playlists and Blends live as the app's own playlists (they play offline, can be
 * downloaded, and every playlist screen edits them), the taste summary for Blends comes from likes and history, and
 * the Jam drives the app's player.
 */
object SocialBridge {
    fun install(model: SpotifeurModel, scope: CoroutineScope) {
        val context = model.getApplication<android.app.Application>()
        val store = model.localPlaylistStore

        // Friends' changes into the local copies.
        scope.launch {
            SharedPlaylists.views.collect { views ->
                views.forEach { view ->
                    val tracks = view.entries.mapNotNull { SharedItems.fromTrack(it.track) }
                    val localId = view.localId ?: (LOCAL_PLAYLIST_PREFIX + "shared-" + view.id)
                    val local = store.get(localId)
                    if (local == null || local.name != view.title || local.tracks.map(Music::id) != tracks.map(Music::id)) {
                        store.put(LocalPlaylist(localId, view.title, model.withLocalFiles(tracks), local?.cover))
                    }
                    // Linked only once the copy exists, so the watcher below never takes it for a deleted one.
                    if (view.localId == null) SharedPlaylists.link(context, view.id, localId)
                }
            }
        }
        // Edits made here (any playlist screen) out to the friends; deleting the copy leaves the shared playlist.
        scope.launch {
            val seen = HashSet<String>()
            store.playlists.combine(SharedPlaylists.views) { lists, views -> lists to views }.collect { (lists, views) ->
                views.forEach { view ->
                    val localId = view.localId ?: return@forEach
                    val local = lists.firstOrNull { it.id == localId }
                    if (local == null) { if (localId in seen) { seen -= localId; SharedPlaylists.leave(context, view.id) }; return@forEach }
                    seen += localId
                    val mine = local.tracks.filter { it.id.startsWith("spotify:track:") || it.id.startsWith("ytm:track:") }
                    if (local.name != view.title || mine.map(Music::id) != view.entries.map { it.track.optString("id") }) {
                        SharedPlaylists.edited(context, view.id, mine.map(SharedItems::track), local.name)
                    }
                }
            }
        }
        // Blends: rebuilt by both phones, kept read-only here.
        scope.launch {
            Blends.views.collect { views ->
                views.filter { it.tracks.isNotEmpty() }.forEach { view ->
                    val localId = view.localId ?: (LOCAL_PLAYLIST_PREFIX + "blend-" + view.id)
                    val name = "${Social.nameOf(view.friend)} + You"
                    val tracks = view.tracks.mapNotNull(SharedItems::fromTrack)
                    val local = store.get(localId)
                    if (local == null || local.name != name || local.tracks.map(Music::id) != tracks.map(Music::id)) {
                        store.put(LocalPlaylist(localId, name, tracks, local?.cover))
                    }
                    if (view.localId == null) Blends.link(context, view.id, localId)
                }
            }
        }
        Blends.taste = { taste(model) }
        Jam.player = JamPlayerImpl(model)
    }

    /** Up to 200 songs: liked ones count three plays, then plays from the history of the last months. */
    private suspend fun taste(model: SpotifeurModel): List<JSONObject> = withContext(Dispatchers.IO) {
        model.listeningHistory.load()
        val plays = HashMap<String, Int>()
        val songs = HashMap<String, Music>()
        model.listeningHistory.entries.value.forEach { entry -> plays.merge(entry.track.id, 1, Int::plus); songs.putIfAbsent(entry.track.id, entry.track) }
        model.library.value.content?.savedTracks.orEmpty().forEach { plays.merge(it.id, 3, Int::plus); songs.putIfAbsent(it.id, it) }
        plays.entries.filter { it.key.startsWith("spotify:track:") || it.key.startsWith("ytm:track:") }
            .sortedByDescending { it.value }.take(200)
            .mapNotNull { (id, count) -> songs[id]?.let { SharedItems.track(it).put("plays", count) } }
    }

    /** Starts sharing a local playlist with friends (they choose to join). */
    suspend fun shareEditable(model: SpotifeurModel, item: Music, friends: List<String>) {
        val context = model.getApplication<android.app.Application>()
        val local = model.localPlaylistStore.get(item.id) ?: throw IllegalArgumentException("Only your own playlists here can be shared to edit together.")
        val tracks = local.tracks.filter { it.id.startsWith("spotify:track:") || it.id.startsWith("ytm:track:") }
        SharedPlaylists.create(context, local.id, local.name, tracks.map(SharedItems::track), friends)
    }

    private class JamPlayerImpl(private val model: SpotifeurModel) : JamPlayer {
        private var saved: Triple<List<Music>, Int, Long>? = null
        override fun now(): JamPlayer.Now? {
            val track = model.player.state.value.track ?: return null
            if (!(track.id.startsWith("spotify:track:") || track.id.startsWith("ytm:track:"))) return null
            // Straight from the player that renders the audio when it plays this track; the controller only estimates.
            val rendering = fr.spotifeur.playback.renderingPlayer?.takeIf { it.currentMediaItem?.mediaId == track.id }
            val readAt = android.os.SystemClock.elapsedRealtime()
            val position = rendering?.currentPosition ?: model.player.exactPositionMs() ?: 0
            val playing = rendering?.isPlaying ?: model.player.isPlayingNow()
            val rate = rendering?.playbackParameters?.speed ?: 1f
            return JamPlayer.Now(SharedItems.track(track), position, playing, readAt, rate, if (rendering != null) "exo" else "ctl")
        }
        override fun upNext(): List<JSONObject> = model.player.state.value.let { s ->
            s.queue.drop(s.index + 1).take(20).filter { it.id.startsWith("spotify:track:") || it.id.startsWith("ytm:track:") }.map(SharedItems::track)
        }
        override fun playQueue(tracks: List<JSONObject>, positionMs: Long, start: Boolean) {
            val songs = tracks.mapNotNull(SharedItems::fromTrack).takeIf { it.isNotEmpty() } ?: return
            val loaded = model.player.state.value
            // The host's song is already next here: move on to it and keep what the player preloaded.
            if (loaded.index + 1 < loaded.queue.size && loaded.queue[loaded.index + 1].id == songs.first().id) model.player.seekToItem(loaded.index + 1)
            else model.play(songs, 0)
            model.player.seek(positionMs.toInt())
            if (!start) model.player.pause()
        }
        override fun pause() = model.player.pause()
        override fun resume() = model.player.resume()
        override fun seek(positionMs: Long) = model.player.seek(positionMs.toInt())
        override fun trim(rate: Float) { fr.spotifeur.playback.dsp.RateTrim.rate = rate }
        override fun addToQueue(track: JSONObject) { SharedItems.fromTrack(track)?.let { model.player.addToQueue(listOf(it)) } }
        override fun saveQueue() {
            val (queue, index) = model.player.context()
            saved = Triple(queue, index, model.player.exactPositionMs() ?: 0)
            model.player.pause()
        }
        override fun restoreQueue() {
            val (queue, index, position) = saved ?: return
            saved = null
            if (queue.isEmpty()) return
            model.play(queue, index)
            model.player.seek(position.toInt())
            model.player.pause()
        }
    }
}
