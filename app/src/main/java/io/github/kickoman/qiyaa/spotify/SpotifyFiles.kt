package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.ExtensionKind
import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.Metadata
import io.github.kickoman.qiyaa.spotify.protocol.PlayableFile
import io.github.kickoman.qiyaa.spotify.protocol.SpotifyTrack
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SpotifyFile(
    val trackId: String,
    val fileId: Bytes,
    val kbps: Int,
    val key: Bytes,
    val urls: List<String>,
) {
    override fun toString(): String = "SpotifyFile($trackId, $kbps kbps, ${urls.size} links)"
}

class SpotifyFiles(
    private val web: SpotifyWeb,
    private val session: SpotifySession,
    private val bitrates: () -> List<Int>,
    private val clock: () -> Long,
    private val ttlMs: Long = TTL_MS,
    private val capacity: Int = CAPACITY,
    private val log: (String) -> Unit = {},
) {
    private data class Key(val trackId: String, val bitrates: List<Int>)

    private class Entry(val file: SpotifyFile, val preparedAtMs: Long)

    private val lock = Any()
    private val prepared = leastRecentlyUsed<Key, Entry>()
    private val asked = leastRecentlyUsed<String, List<Int>>()

    val userAgent: String get() = web.userAgent

    suspend fun prepare(trackId: String): SpotifyFile {
        val now = clock()
        val wanted: List<Int>
        synchronized(lock) {
            wanted = bitrates()
            require(wanted.isNotEmpty()) { "no bitrates to ask track $trackId in" }
            asked[trackId] = wanted
            val cached = prepared[Key(trackId, wanted)]
            if (cached != null && now - cached.preparedAtMs < ttlMs) return cached.file
        }
        val file = fetch(trackId, wanted)
        synchronized(lock) { prepared[Key(trackId, wanted)] = Entry(file, now) }
        return file
    }

    suspend fun storageUrls(file: SpotifyFile): List<String> = storage(file.trackId, file.fileId)

    fun askedInOtherQuality(trackId: String): Boolean {
        synchronized(lock) {
            val last = asked[trackId] ?: return false
            return last != bitrates()
        }
    }

    fun report(problem: SpotifyProblem) = web.report(problem)

    private suspend fun fetch(trackId: String, wanted: List<Int>): SpotifyFile {
        val uri = Ids.trackUri(trackId) ?: throw NoPlayableFileException(trackId, "an id that is not base62")
        val track = metadataOf(uri) ?: throw NoPlayableFileException(trackId, "no metadata to play")
        val playable = pick(track, wanted) ?: throw NoPlayableFileException(trackId)
        val kbps = playable.file.format.oggVorbisKbps
        val key = session.audioKey(playable.track, playable.file.id)
        val urls = storage(trackId, playable.file.id)
        log("the file of track $trackId: Ogg Vorbis $kbps kbps, ${urls.size} CDN links")
        return SpotifyFile(trackId, playable.file.id, kbps, key, urls)
    }

    private suspend fun metadataOf(uri: String): SpotifyTrack? {
        val request =
            WebRequest(
                path = SpotifyLibrary.METADATA_PATH,
                method = "POST",
                body = Bytes.of(Metadata.request(listOf(uri), ExtensionKind.TRACK_V4)),
            )
        val entities =
            Metadata.parseReply(web.spclient(request))
                ?: throw web.unreadable(request.method, request.label, "entities that do not parse")
        val entity = entities.firstOrNull { it.uri == uri && it.found } ?: return null
        return Metadata.parseTrack(entity.payload)
    }

    private suspend fun storage(trackId: String, fileId: Bytes): List<String> {
        val request = WebRequest(path = STORAGE_RESOLVE_PATH + fileId.hex())
        val storage =
            Metadata.parseStorageResolve(web.spclient(request))
                ?: throw web.unreadable(request.method, request.label, "a reply that does not parse")
        val urls = storage.urls.filter { it.toHttpUrlOrNull() != null }
        if (!storage.usable || urls.isEmpty()) {
            throw NoPlayableFileException(
                trackId,
                "no CDN link to its file: result ${storage.result.code}, " +
                    "${urls.size} of ${storage.urls.size} links usable",
            )
        }
        return urls
    }

    private fun <K, V> leastRecentlyUsed(): LinkedHashMap<K, V> =
        object : LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
        }

    companion object {
        const val TTL_MS = 15 * 60 * 1_000L
        const val CAPACITY = 64
        const val STORAGE_RESOLVE_PATH = "/storage-resolve/files/audio/interactive/"
        private const val LOAD_FACTOR = 0.75f

        fun pick(track: SpotifyTrack, bitrates: List<Int>): PlayableFile? =
            bitrates.firstNotNullOfOrNull { kbps ->
                Metadata.pickOggVorbis(track, kbps)?.takeIf { it.file.format.oggVorbisKbps == kbps }
            } ?: Metadata.pickOggVorbis(track, bitrates.firstOrNull() ?: 0)
    }
}
