package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.local.LocalFolder
import io.github.kickoman.qiyaa.local.LocalRow
import io.github.kickoman.qiyaa.local.LocalTracks

const val PRIMARY = "external_primary"
const val CARD = "1234-ABCD"

fun localRow(
    id: Long,
    title: String?,
    album: String,
    albumId: Long,
    trackNumber: Int,
    vararg path: String,
    volume: String = PRIMARY,
    file: String,
    artist: String? = LocalTracks.UNKNOWN,
    sizeBytes: Long = 1_000_000,
    durationMs: Long = 100_000,
) = LocalRow(
    id = id,
    title = title,
    displayName = file,
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = durationMs,
    trackNumber = trackNumber,
    mimeType = "audio/flac",
    sizeBytes = sizeBytes,
    bitrateBitsPerSecond = null,
    folder = LocalFolder(volume, path.toList()),
)
