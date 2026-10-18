package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.yandex.Quality

class PlayLink(private val links: TrackUrlCache, private val bitrate: CurrentBitrate) {
    @Volatile
    private var quality: Quality? = null

    suspend fun url(trackId: String): String {
        val kept = quality
        val signed = links.get(trackId, kept)
        if (kept == null) {
            quality = signed.quality
            bitrate.onResolved(trackId, signed.link.bitrateKbps)
        }
        return signed.link.url
    }
}
