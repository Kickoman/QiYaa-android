package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.Image
import io.github.kickoman.qiyaa.spotify.protocol.Metadata
import io.github.kickoman.qiyaa.spotify.protocol.SpotifyTrack
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

object SpotifyTracks {
    const val COVER_HOST = "https://i.scdn.co/image/"
    const val COVER_PIXELS = 400
    const val ANY_BITRATE_KBPS = 320

    fun trackOf(track: SpotifyTrack): Track = Track(
        id = Ids.base62FromGid(track.gid),
        title = track.name,
        artists = track.artists.map { it.name },
        albumId = "",
        durationMs = track.durationMs,
        available = isAvailable(track),
        coverUrl = coverUrlOf(track.album.covers, COVER_PIXELS),
        source = TrackSource.SPOTIFY,
    )

    fun isAvailable(track: SpotifyTrack): Boolean = Metadata.pickOggVorbis(track, ANY_BITRATE_KBPS) != null

    fun coverUrlOf(covers: List<Image>, pixels: Int): String? {
        var best: Image? = null
        for (cover in covers) {
            val current = best
            val better =
                current == null ||
                    if (cover.pixels >= pixels) {
                        current.pixels < pixels || cover.pixels < current.pixels
                    } else {
                        current.pixels < pixels && cover.pixels > current.pixels
                    }
            if (better) best = cover
        }
        return best?.let { COVER_HOST + it.id.hex() }
    }

    fun uriOf(track: Track): String = Ids.TRACK_URI_PREFIX + track.id
}
