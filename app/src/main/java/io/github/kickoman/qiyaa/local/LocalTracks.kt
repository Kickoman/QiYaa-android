package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

data class LocalRow(
    val id: Long,
    val title: String?,
    val displayName: String,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val mimeType: String?,
    val sizeBytes: Long,
    val bitrateBitsPerSecond: Long?,
    val folder: LocalFolder,
)

data class LocalFolder(val volume: String, val path: List<String>)

object LocalTracks {
    const val UNKNOWN = "<unknown>"
    const val FLAC = "audio/flac"
    private const val MEDIA_URI = "content://media/external/audio/media/"
    private val TAKEN =
        setOf(
            "audio/mpeg",
            "audio/wav",
            "audio/x-wav",
            "audio/mp4",
            "audio/x-m4a",
            "audio/ogg",
            "application/ogg",
            FLAC,
            "audio/x-flac",
        )

    fun isTaken(mimeType: String?, flacDecodes: Boolean): Boolean {
        val type = mimeType?.lowercase() ?: return false
        if (type !in TAKEN) return false
        return flacDecodes || (type != FLAC && type != "audio/x-flac")
    }

    fun toTrack(row: LocalRow): Track = Track(
        id = row.id.toString(),
        title = title(row),
        artists = listOfNotNull(row.artist?.takeIf { it.isNotBlank() && it != UNKNOWN }),
        durationMs = row.durationMs.coerceAtLeast(0),
        coverUrl = "$MEDIA_URI${row.id}/albumart",
        source = TrackSource.LOCAL,
        bitrateKbps = bitrateKbps(row),
    )

    fun title(row: LocalRow): String = row.title?.takeIf { it.isNotBlank() }
        ?: row.displayName.substringBeforeLast('.').ifEmpty { row.displayName }

    fun bitrateKbps(row: LocalRow): Int {
        row.bitrateBitsPerSecond?.takeIf { it > 0 }?.let { return (it / 1_000).toInt() }
        if (row.durationMs <= 0 || row.sizeBytes <= 0) return 0
        return (row.sizeBytes * 8 / row.durationMs).toInt()
    }
}
