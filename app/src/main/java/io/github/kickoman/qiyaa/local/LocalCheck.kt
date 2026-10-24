package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

object LocalCheck {
    fun localIds(tracks: List<Track>): List<Long> =
        tracks.filter { it.source == TrackSource.LOCAL }.mapNotNull { it.id.toLongOrNull() }.distinct()

    fun renumbered(saved: List<Track>, found: Map<Long, LocalRow>): Boolean = saved.any { track ->
        val row = track.id.toLongOrNull()?.let(found::get)
        track.source == TrackSource.LOCAL && row != null &&
            (LocalTracks.title(row) != track.title || row.durationMs.coerceAtLeast(0) != track.durationMs)
    }
}
