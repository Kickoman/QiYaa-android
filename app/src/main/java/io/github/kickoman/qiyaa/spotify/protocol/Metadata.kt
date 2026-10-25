package io.github.kickoman.qiyaa.spotify.protocol

import kotlin.math.abs

enum class ExtensionKind(val code: Int) {
    ARTIST_V4(8),
    ALBUM_V4(9),
    TRACK_V4(10),
}

@JvmInline
value class AudioFormat(val code: Int) {
    val oggVorbisKbps: Int
        get() = when (this) {
            OGG_VORBIS_96 -> 96
            OGG_VORBIS_160 -> 160
            OGG_VORBIS_320 -> 320
            else -> 0
        }

    companion object {
        val OGG_VORBIS_96 = AudioFormat(0)
        val OGG_VORBIS_160 = AudioFormat(1)
        val OGG_VORBIS_320 = AudioFormat(2)
    }
}

data class AudioFileRef(val id: Bytes, val format: AudioFormat)

data class Image(val id: Bytes, val pixels: Int)

data class ArtistRef(val gid: Bytes = ZERO_GID, val name: String = "")

data class AlbumRef(
    val gid: Bytes = ZERO_GID,
    val name: String = "",
    val year: Int = 0,
    val covers: List<Image> = emptyList(),
)

data class SpotifyTrack(
    val gid: Bytes = ZERO_GID,
    val name: String = "",
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef = AlbumRef(),
    val durationMs: Long = 0,
    val files: List<AudioFileRef> = emptyList(),
    val alternatives: List<SpotifyTrack> = emptyList(),
)

data class SpotifyAlbum(
    val gid: Bytes = ZERO_GID,
    val name: String = "",
    val artists: List<ArtistRef> = emptyList(),
    val tracks: List<Bytes> = emptyList(),
)

data class TopTracks(val country: String, val tracks: List<Bytes>)

data class SpotifyArtist(
    val gid: Bytes = ZERO_GID,
    val name: String = "",
    val topTracks: List<TopTracks> = emptyList(),
)

data class MetadataEntity(val uri: String, val status: Int, val payload: Bytes) {
    val found: Boolean get() = status == FOUND && !payload.isEmpty()

    companion object {
        const val FOUND = 200
    }
}

data class PlayableFile(val track: Bytes, val file: AudioFileRef)

@JvmInline
value class StorageResult(val code: Int) {
    companion object {
        val CDN = StorageResult(0)
        val STORAGE = StorageResult(1)
        val RESTRICTED = StorageResult(3)
    }
}

data class StorageUrls(val result: StorageResult, val urls: List<String>) {
    val usable: Boolean get() = result == StorageResult.CDN && urls.isNotEmpty()
}

object Metadata {
    const val WORLDWIDE = "ZZ"
    const val MAX_ALTERNATIVE_DEPTH = 4

    fun request(uris: List<String>, kind: ExtensionKind): ByteArray {
        val query = ProtoWriter().varint(ExtensionQueryFields.EXTENSION_KIND, kind.code.toLong())
        val request = ProtoWriter()
        for (uri in uris) {
            val entity = ProtoWriter()
                .string(EntityRequestFields.ENTITY_URI, uri)
                .message(EntityRequestFields.QUERY, query)
            request.message(BatchedEntityRequestFields.ENTITY_REQUEST, entity)
        }
        return request.toByteArray()
    }

    fun parseReply(body: ByteArray): List<MetadataEntity>? {
        val entities = ArrayList<MetadataEntity>()
        val response = ProtoReader(body)
        while (response.next()) {
            if (response.field != BatchedExtensionResponseFields.EXTENDED_METADATA) continue
            val array = response.message()
            while (array.next()) {
                if (array.field != EntityExtensionDataArrayFields.EXTENSION_DATA) continue
                entities += entityOf(array.message()) ?: return null
            }
            if (array.failed) return null
        }
        return entities.takeUnless { response.failed }
    }

    fun parseTrack(payload: Bytes): SpotifyTrack? = trackOf(ProtoReader(payload.toByteArray()), depth = 0)

    fun parseAlbum(payload: Bytes): SpotifyAlbum? {
        var gid = ZERO_GID
        var name = ""
        val artists = ArrayList<ArtistRef>()
        val tracks = ArrayList<Bytes>()
        val reader = ProtoReader(payload.toByteArray())
        while (reader.next()) {
            when (reader.field) {
                AlbumFields.GID -> gid = gidOr(reader, gid)
                AlbumFields.NAME -> name = reader.string()
                AlbumFields.ARTIST -> artists += artistRefOf(reader.message())
                AlbumFields.DISC -> tracks += discOf(reader.message()) ?: return null
            }
        }
        return SpotifyAlbum(gid, name, artists, tracks).takeUnless { reader.failed }
    }

    fun parseArtist(payload: Bytes): SpotifyArtist? {
        var gid = ZERO_GID
        var name = ""
        val topTracks = ArrayList<TopTracks>()
        val reader = ProtoReader(payload.toByteArray())
        while (reader.next()) {
            when (reader.field) {
                ArtistFields.GID -> gid = gidOr(reader, gid)
                ArtistFields.NAME -> name = reader.string()
                ArtistFields.TOP_TRACK -> topTracks += topTracksOf(reader.message()) ?: return null
            }
        }
        return SpotifyArtist(gid, name, topTracks).takeUnless { reader.failed }
    }

    fun topTracksIn(artist: SpotifyArtist, country: String): List<Bytes> {
        for (wanted in listOf(country, WORLDWIDE, "")) {
            val top = artist.topTracks.firstOrNull { it.country == wanted && it.tracks.isNotEmpty() }
            if (top != null) return top.tracks
        }
        return emptyList()
    }

    fun pickOggVorbis(track: SpotifyTrack, preferredKbps: Int): PlayableFile? =
        pickOggVorbis(track, preferredKbps, depth = 0)

    fun parseStorageResolve(body: ByteArray): StorageUrls? {
        var result = StorageResult.CDN
        val urls = ArrayList<String>()
        val reader = ProtoReader(body)
        while (reader.next()) {
            when (reader.field) {
                StorageResolveFields.RESULT -> result = StorageResult(reader.varint.toInt())
                StorageResolveFields.CDN_URL -> urls += reader.string()
            }
        }
        return StorageUrls(result, urls).takeUnless { reader.failed }
    }

    private fun entityOf(reader: ProtoReader): MetadataEntity? {
        var uri = ""
        var status = 0
        var payload = Bytes.EMPTY
        while (reader.next()) {
            when (reader.field) {
                EntityExtensionDataFields.HEADER -> status = statusOf(reader.message()) ?: status
                EntityExtensionDataFields.ENTITY_URI -> uri = reader.string()
                EntityExtensionDataFields.EXTENSION_DATA -> payload = payloadOf(reader.message()) ?: payload
            }
        }
        return MetadataEntity(uri, status, payload).takeUnless { reader.failed }
    }

    private fun statusOf(header: ProtoReader): Int? {
        var status: Int? = null
        while (header.next()) {
            if (header.field == EntityExtensionDataHeaderFields.STATUS_CODE) status = header.varint.toInt()
        }
        return status
    }

    private fun payloadOf(any: ProtoReader): Bytes? {
        var payload: Bytes? = null
        while (any.next()) {
            if (any.field == AnyFields.VALUE) payload = Bytes.of(any.bytes())
        }
        return payload
    }

    private fun pickOggVorbis(track: SpotifyTrack, preferredKbps: Int, depth: Int): PlayableFile? {
        val own = track.files.filter { it.format.oggVorbisKbps > 0 }
            .minByOrNull { abs(it.format.oggVorbisKbps - preferredKbps) }
        if (own != null) return PlayableFile(track.gid, own)
        if (depth >= MAX_ALTERNATIVE_DEPTH) return null
        return track.alternatives.firstNotNullOfOrNull { pickOggVorbis(it, preferredKbps, depth + 1) }
    }

    private fun trackOf(reader: ProtoReader, depth: Int): SpotifyTrack? {
        var gid = ZERO_GID
        var name = ""
        var album = AlbumRef()
        var durationMs = 0L
        val artists = ArrayList<ArtistRef>()
        val files = ArrayList<AudioFileRef>()
        val alternatives = ArrayList<SpotifyTrack>()
        while (reader.next()) {
            when (reader.field) {
                TrackFields.GID -> gid = gidOr(reader, gid)

                TrackFields.NAME -> name = reader.string()

                TrackFields.ALBUM -> album = albumRefOf(reader.message())

                TrackFields.ARTIST -> artists += artistRefOf(reader.message())

                TrackFields.DURATION -> durationMs = reader.zigzag

                TrackFields.FILE -> audioFileOf(reader.message())?.let(files::add)

                TrackFields.ALTERNATIVE -> {
                    if (depth < MAX_ALTERNATIVE_DEPTH) {
                        trackOf(reader.message(), depth + 1)?.let(alternatives::add)
                    }
                }
            }
        }
        if (reader.failed) return null
        return SpotifyTrack(gid, name, artists, album, durationMs, files, alternatives)
    }

    private fun albumRefOf(reader: ProtoReader): AlbumRef {
        var gid = ZERO_GID
        var name = ""
        var year = 0
        val covers = ArrayList<Image>()
        while (reader.next()) {
            when (reader.field) {
                AlbumFields.GID -> gid = gidOr(reader, gid)
                AlbumFields.NAME -> name = reader.string()
                AlbumFields.DATE -> year = yearOf(reader.message())
                AlbumFields.COVER_GROUP -> covers += imagesOf(reader.message())
            }
        }
        return AlbumRef(gid, name, year, covers)
    }

    private fun artistRefOf(reader: ProtoReader): ArtistRef {
        var gid = ZERO_GID
        var name = ""
        while (reader.next()) {
            when (reader.field) {
                ArtistFields.GID -> gid = gidOr(reader, gid)
                ArtistFields.NAME -> name = reader.string()
            }
        }
        return ArtistRef(gid, name)
    }

    private fun yearOf(date: ProtoReader): Int {
        while (date.next()) {
            if (date.field == DateFields.YEAR) return date.zigzag.toInt()
        }
        return 0
    }

    private fun imagesOf(group: ProtoReader): List<Image> {
        val images = ArrayList<Image>()
        while (group.next()) {
            if (group.field == ImageGroupFields.IMAGE) imageOf(group.message())?.let(images::add)
        }
        return images
    }

    private fun imageOf(reader: ProtoReader): Image? {
        var id: Bytes? = null
        var size = 0L
        var width = 0
        while (reader.next()) {
            when (reader.field) {
                ImageFields.FILE_ID -> {
                    val bytes = reader.bytes()
                    id = if (bytes.size == Ids.FILE_ID_SIZE) Bytes.of(bytes) else null
                }

                ImageFields.SIZE -> size = reader.varint

                ImageFields.WIDTH -> width = reader.zigzag.toInt()
            }
        }
        if (reader.failed || id == null) return null
        return Image(id, if (width > 0) width else pixelsOfSize(size))
    }

    private fun audioFileOf(reader: ProtoReader): AudioFileRef? {
        var id: Bytes? = null
        var format = AudioFormat(0)
        while (reader.next()) {
            when (reader.field) {
                AudioFileFields.FILE_ID -> {
                    val bytes = reader.bytes()
                    if (bytes.size == Ids.FILE_ID_SIZE) id = Bytes.of(bytes)
                }

                AudioFileFields.FORMAT -> format = AudioFormat(reader.varint.toInt())
            }
        }
        val fileId = id
        if (reader.failed || fileId == null) return null
        return AudioFileRef(fileId, format)
    }

    private fun discOf(reader: ProtoReader): List<Bytes>? {
        val tracks = ArrayList<Bytes>()
        while (reader.next()) {
            if (reader.field == DiscFields.TRACK) tracks += gidIn(reader.message())
        }
        return tracks.takeUnless { reader.failed }
    }

    private fun topTracksOf(reader: ProtoReader): TopTracks? {
        var country = ""
        val tracks = ArrayList<Bytes>()
        while (reader.next()) {
            when (reader.field) {
                TopTracksFields.COUNTRY -> country = reader.string()
                TopTracksFields.TRACK -> tracks += gidIn(reader.message())
            }
        }
        return TopTracks(country, tracks).takeUnless { reader.failed }
    }

    private fun gidIn(reader: ProtoReader): Bytes {
        var gid = ZERO_GID
        while (reader.next()) {
            if (reader.field == TrackFields.GID) gid = gidOr(reader, gid)
        }
        return gid
    }

    private fun gidOr(reader: ProtoReader, current: Bytes): Bytes {
        val bytes = reader.bytes()
        return if (bytes.size == Ids.GID_SIZE) Bytes.of(bytes) else current
    }

    private fun pixelsOfSize(size: Long): Int = when (size) {
        1L -> 64
        2L -> 640
        3L -> 1280
        else -> 300
    }
}

private val ZERO_GID = Bytes.of(ByteArray(Ids.GID_SIZE))

private object BatchedEntityRequestFields {
    const val ENTITY_REQUEST = 2
}

private object EntityRequestFields {
    const val ENTITY_URI = 1
    const val QUERY = 2
}

private object ExtensionQueryFields {
    const val EXTENSION_KIND = 1
}

private object BatchedExtensionResponseFields {
    const val EXTENDED_METADATA = 2
}

private object EntityExtensionDataArrayFields {
    const val EXTENSION_DATA = 3
}

private object EntityExtensionDataFields {
    const val HEADER = 1
    const val ENTITY_URI = 2
    const val EXTENSION_DATA = 3
}

private object EntityExtensionDataHeaderFields {
    const val STATUS_CODE = 1
}

private object AnyFields {
    const val VALUE = 2
}

private object TrackFields {
    const val GID = 1
    const val NAME = 2
    const val ALBUM = 3
    const val ARTIST = 4
    const val DURATION = 7
    const val FILE = 12
    const val ALTERNATIVE = 13
}

private object AlbumFields {
    const val GID = 1
    const val NAME = 2
    const val ARTIST = 3
    const val DATE = 6
    const val DISC = 11
    const val COVER_GROUP = 17
}

private object ArtistFields {
    const val GID = 1
    const val NAME = 2
    const val TOP_TRACK = 4
}

private object DateFields {
    const val YEAR = 1
}

private object ImageGroupFields {
    const val IMAGE = 1
}

private object ImageFields {
    const val FILE_ID = 1
    const val SIZE = 2
    const val WIDTH = 3
}

private object AudioFileFields {
    const val FILE_ID = 1
    const val FORMAT = 2
}

private object DiscFields {
    const val TRACK = 3
}

private object TopTracksFields {
    const val COUNTRY = 1
    const val TRACK = 2
}

private object StorageResolveFields {
    const val RESULT = 1
    const val CDN_URL = 2
}
