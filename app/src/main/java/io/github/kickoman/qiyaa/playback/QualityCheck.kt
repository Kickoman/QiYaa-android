package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

object QualityCheck {
    fun askedInOtherQuality(
        track: Track,
        yandex: (trackId: String) -> Boolean,
        spotify: (trackId: String) -> Boolean,
    ): Boolean = when (track.source) {
        TrackSource.YANDEX -> yandex(track.id)
        TrackSource.SPOTIFY -> spotify(track.id)
        TrackSource.LOCAL -> false
    }
}
