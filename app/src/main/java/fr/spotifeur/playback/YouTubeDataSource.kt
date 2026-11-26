package fr.spotifeur.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import fr.spotifeur.data.AudioFileRecord
import fr.spotifeur.data.AudioIndex
import fr.spotifeur.data.Music
import fr.spotifeur.data.YOUTUBE_FILE_PREFIX
import fr.spotifeur.data.isLocalTrack
import fr.spotifeur.data.sources.Copy
import fr.spotifeur.data.sources.CopyResolver
import fr.spotifeur.data.sources.MonochromeTracks
import fr.spotifeur.data.youtube.MatchQuery
import fr.spotifeur.data.youtube.TrackMatcher
import fr.spotifeur.data.youtube.YouTubeFailure
import fr.spotifeur.data.youtube.YouTubeMusic
import fr.spotifeur.data.youtube.YouTubeStream
import fr.spotifeur.data.youtube.youTubeVideoId
import java.io.IOException

/**
 * Addresses of tracks read from YouTube Music: `ytm://track/<videoId>` for a YouTube Music track,
 * `ytm://match/<id>?…` for a Spotify track played without a Spotify account, found by [TrackMatcher].
 */
internal object YouTubeUris {
    const val SCHEME = "ytm"

    fun isYouTube(uri: Uri?): Boolean = uri?.scheme == SCHEME

    /** The address that plays [track] from YouTube Music, or null when it plays another way. */
    fun of(track: Music, spotifySignedIn: Boolean, lossless: Boolean = AudioRoute.losslessWanted()): Uri? {
        track.youTubeVideoId?.let { return Uri.parse("$SCHEME://track/$it") }
        if (!AudioRoute.viaCopy(track, spotifySignedIn, lossless)) return null
        return Uri.Builder().scheme(SCHEME).authority("match").appendPath(track.id)
            .appendQueryParameter("title", track.title).appendQueryParameter("artists", track.subtitle)
            .appendQueryParameter("duration", track.durationMs.toString()).appendQueryParameter("album", track.album)
            .apply { track.isrc?.let { appendQueryParameter("isrc", it) } }
            .apply { if (spotifySignedIn) appendQueryParameter("strict", "1") }
            .apply { if (lossless) appendQueryParameter("q", "lossless") }
            .build()
    }

    /** Whether [uri] asks for the lossless (FLAC) copy. */
    fun lossless(uri: Uri?): Boolean = uri?.getQueryParameter("q") == "lossless"

    /** The file name the caches know this track's audio by. */
    fun fileHex(trackId: String, lossless: Boolean = false): String =
        YOUTUBE_FILE_PREFIX + (trackId.substringAfter("ytm:track:", "").ifEmpty { "sp-" + trackId.substringAfterLast(':') }) +
            if (lossless) "-flac" else ""

    @androidx.annotation.OptIn(UnstableApi::class)
    /** The name a track's copy is cached under: the FLAC copy has its own, so neither is ever served as the other. */
    fun cacheKey(trackId: String, lossless: Boolean = false): String = AudioCache.key(fileHex(trackId, lossless))

    /** The track id an address plays, as the queue and the indexes know it. */
    fun trackId(uri: Uri): String? = when (uri.authority) {
        "track" -> uri.lastPathSegment?.let { "ytm:track:$it" }
        "match" -> uri.lastPathSegment
        else -> null
    }

    fun matchQuery(uri: Uri): MatchQuery? {
        if (uri.authority != "match") return null
        val id = uri.lastPathSegment ?: return null
        return MatchQuery(
            key = id, title = uri.getQueryParameter("title").orEmpty(), artists = uri.getQueryParameter("artists").orEmpty(),
            durationMs = uri.getQueryParameter("duration")?.toIntOrNull() ?: 0, album = uri.getQueryParameter("album").orEmpty(),
            isrc = uri.getQueryParameter("isrc"), strict = uri.getQueryParameter("strict") == "1",
        )
    }
}

/**
 * Reads a YouTube Music audio stream: resolves its address (once per few hours), then reads it in
 * bounded ranges, since the CDN throttles or refuses open-ended requests. A refused address is
 * resolved again through another client.
 */
@UnstableApi
internal class YouTubeDataSource private constructor(private val context: Context) : BaseDataSource(true) {
    class Factory(context: Context) : DataSource.Factory {
        private val app = context.applicationContext
        init { YouTubeMusic.init(app); TrackMatcher.init(app); CopyResolver.init(app) }
        override fun createDataSource(): DataSource = YouTubeDataSource(app)
    }

    private var delegate: HttpDataSource? = null
    private var stream: YouTubeStream? = null
    private var spec: DataSpec? = null
    private var position = 0L
    private var requestEnd = UNSET
    private var resourceEnd = UNSET
    private var bytesLeftInRange = 0L
    private var rangeYielded = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        val trackId = YouTubeUris.trackId(uri) ?: throw IOException("Unknown YouTube Music address.")
        val videoId = when (uri.authority) {
            "track" -> checkNotNull(uri.lastPathSegment)
            else -> when (val copy = YouTubeUris.matchQuery(uri)?.let(CopyResolver::resolve)) {
                is Copy.YouTube -> copy.videoId
                is Copy.Monochrome -> try { return openDirect(dataSpec, trackId, copy) } catch (failure: IOException) {
                    // Nothing of this copy is kept yet: the next copy can take its place under the same key.
                    val key = YouTubeUris.cacheKey(trackId, YouTubeUris.lossless(uri))
                    val kept = AudioCache.cache(context).getCachedBytes(key, 0, Long.MAX_VALUE) +
                        DownloadCenter.cache(context).getCachedBytes(key, 0, Long.MAX_VALUE)
                    android.util.Log.i("SpotifeurCopy", "$trackId monochrome failed (${failure.javaClass.simpleName}: ${failure.message}) kept=$kept")
                    if (kept > 0) throw failure
                    YouTubeUris.matchQuery(uri)?.let(CopyResolver::demote)?.videoId ?: throw failure
                }
                null -> throw YouTubeFailure("No copy of this track was found.", "NO_MATCH")
            }
        }
        position = dataSpec.position
        requestEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else UNSET
        val excluded = HashSet<String>()
        var failure: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val current = YouTubeMusic.stream(videoId, excluded)
            stream = current
            resourceEnd = if (current.contentLength > 0) current.contentLength else UNSET
            if (resourceEnd != UNSET && position > resourceEnd) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            try {
                delegate = OkHttpDataSource.Factory(YouTubeMusic.http).setUserAgent(current.userAgent).createDataSource()
                openNextRange()
                if (resourceEnd != UNSET) AudioIndex.get(context).attachDirectFile(trackId,
                    AudioFileRecord(YouTubeUris.fileHex(trackId, YouTubeUris.lossless(dataSpec.uri)), resourceEnd, ""))
                opened = true
                transferStarted(dataSpec)
                val end = knownEnd()
                return if (end == UNSET) C.LENGTH_UNSET.toLong() else end - dataSpec.position
            } catch (refused: HttpDataSource.InvalidResponseCodeException) {
                if (refused.responseCode !in REFUSED) throw refused
                delegate?.close(); delegate = null
                YouTubeMusic.forgetStream(videoId)
                excluded += current.client
                if (attempt == 0) YouTubeMusic.renewIdentity()
                failure = refused
            }
        }
        throw failure ?: YouTubeFailure("The audio of this track could not be opened.")
    }

    /** Reads a copy served with plain byte ranges, in the same bounded ranges as YouTube Music. */
    private fun openDirect(dataSpec: DataSpec, trackId: String, copy: Copy.Monochrome): Long {
        position = dataSpec.position
        requestEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else UNSET
        val wantsLossless = YouTubeUris.lossless(dataSpec.uri)
        val key = YouTubeUris.cacheKey(trackId, wantsLossless)
        // FLAC first when asked. Refused before any of it is kept: the AAC copy takes its place under the same name
        // (one whole file per name). Once bytes are kept, a refusal goes up, to the engine fallback.
        val nothingKept = { AudioCache.cache(context).getCachedBytes(key, 0, Long.MAX_VALUE) + DownloadCenter.cache(context).getCachedBytes(key, 0, Long.MAX_VALUE) <= 0 }
        val attempts = if (wantsLossless) listOf(true, false) else listOf(false)
        var refusal: IOException? = null
        for (lossless in attempts) {
            if (lossless != attempts.first() && !nothingKept()) break
            if (!MonochromeTracks.gate.open()) { refusal = YouTubeFailure("The copy service asked to wait.", "COPY_REFUSED"); break }
            resourceEnd = UNSET
            stream = YouTubeStream(copy.id, MonochromeTracks.streamUrl(copy.id, lossless), "", "monochrome", 0L,
                if (lossless) "audio/flac" else "audio/mp4", (if (lossless) MonochromeTracks.LOSSLESS_BITRATE else MonochromeTracks.BITRATE).toLong(),
                44_100, Long.MAX_VALUE)
            delegate = OkHttpDataSource.Factory(MonochromeTracks.http).createDataSource()
            try { openNextRange(); MonochromeTracks.gate.served(); refusal = null; break } catch (failure: IOException) {
                delegate?.close(); delegate = null
                if (failure is HttpDataSource.InvalidResponseCodeException) {
                    if (failure.responseCode == 429) MonochromeTracks.gate.refused(failure.headerFields["Retry-After"]?.firstOrNull()?.toLongOrNull())
                    android.util.Log.i("SpotifeurCopy", "$trackId monochrome ${if (lossless) "flac" else "aac"} refused (HTTP ${failure.responseCode})")
                    refusal = YouTubeFailure("The copy of this track could not be opened (HTTP ${failure.responseCode}).", "COPY_REFUSED")
                } else { refusal = failure; break }
            }
        }
        refusal?.let { throw it }
        if (resourceEnd != UNSET) AudioIndex.get(context).attachDirectFile(trackId, AudioFileRecord(YouTubeUris.fileHex(trackId, wantsLossless), resourceEnd, ""))
        opened = true
        transferStarted(dataSpec)
        val end = knownEnd()
        return if (end == UNSET) C.LENGTH_UNSET.toLong() else end - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val source = delegate ?: return C.RESULT_END_OF_INPUT
        if (bytesLeftInRange <= 0L) {
            val end = knownEnd()
            if (end != UNSET && position >= end) return C.RESULT_END_OF_INPUT
            source.close()
            openNextRange()
            if (bytesLeftInRange <= 0L) return C.RESULT_END_OF_INPUT
        }
        val read = source.read(buffer, offset, minOf(length.toLong(), bytesLeftInRange).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            // The range ended early: open the next one from where reading stopped.
            if (!rangeYielded) return C.RESULT_END_OF_INPUT
            bytesLeftInRange = 0L
            return read(buffer, offset, length)
        }
        rangeYielded = true
        position += read
        bytesLeftInRange -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        bytesLeftInRange = 0L
        try { delegate?.close() } finally {
            delegate = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    private fun knownEnd(): Long = when {
        requestEnd != UNSET && resourceEnd != UNSET -> minOf(requestEnd, resourceEnd)
        requestEnd != UNSET -> requestEnd
        else -> resourceEnd
    }

    private fun openNextRange() {
        val source = checkNotNull(delegate)
        val address = checkNotNull(stream).url
        val end = knownEnd()
        val length = minOf(RANGE_BYTES, if (end != UNSET) end - position else Long.MAX_VALUE)
        rangeYielded = false
        if (length <= 0L) { bytesLeftInRange = 0L; return }
        try {
            source.open(DataSpec.Builder().setUri(address).setPosition(position).setLength(length).build())
        } catch (failure: HttpDataSource.InvalidResponseCodeException) {
            if (failure.responseCode == 416) { bytesLeftInRange = 0L; return }
            throw failure
        }
        val served = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            ?.let { CONTENT_RANGE.find(it) }
        if (served != null) {
            val (_, last, total) = served.destructured
            bytesLeftInRange = last.toLong() - position + 1
            total.toLongOrNull()?.let { resourceEnd = it }
        } else bytesLeftInRange = length
    }

    private companion object {
        /** The CDN serves ranges of about a megabyte at full speed and slows down larger ones. */
        const val RANGE_BYTES = 900_000L
        const val MAX_ATTEMPTS = 3
        val UNSET = C.LENGTH_UNSET.toLong()
        val REFUSED = setOf(401, 403, 404, 410)
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}

/** Sends every address to the source that reads it: YouTube Music, or the Spotify chain. */
@UnstableApi
internal class RoutingDataSourceFactory(
    private val spotify: DataSource.Factory,
    private val youtube: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(spotify, youtube)
}

@UnstableApi
private class RoutingDataSource(
    private val spotifyFactory: DataSource.Factory,
    private val youtubeFactory: DataSource.Factory,
) : DataSource {
    private val listeners = ArrayList<androidx.media3.datasource.TransferListener>()
    private var current: DataSource? = null
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) { listeners += transferListener }
    override fun open(dataSpec: DataSpec): Long {
        val source = (if (YouTubeUris.isYouTube(dataSpec.uri)) youtubeFactory else spotifyFactory).createDataSource()
        listeners.forEach(source::addTransferListener)
        current = source
        return source.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(current).read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()
    override fun close() { try { current?.close() } finally { current = null } }
}
