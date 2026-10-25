package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

val Track.webUrl: String?
    get() = when (source) {
        TrackSource.YANDEX ->
            if (albumId.isEmpty()) {
                "https://music.yandex.ru/track/$id"
            } else {
                "https://music.yandex.ru/album/$albumId/track/$id"
            }

        TrackSource.LOCAL -> null

        TrackSource.SPOTIFY -> "https://open.spotify.com/track/$id"
    }
