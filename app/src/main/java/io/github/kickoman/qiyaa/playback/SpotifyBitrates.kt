package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.yandex.Quality

object SpotifyBitrates {
    val STANDARD = listOf(320, 160)
    val ECONOMY = listOf(96, 160)

    fun of(quality: Quality): List<Int> = when (quality) {
        Quality.STANDARD -> STANDARD
        Quality.ECONOMY -> ECONOMY
    }
}
