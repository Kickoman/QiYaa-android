package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track

interface PlayerEngine {
    val itemCount: Int
    val currentIndex: Int
    val isEnded: Boolean
    var shuffleEnabled: Boolean
    var repeatEnabled: Boolean

    fun hasNext(): Boolean

    fun playOrder(): List<Int>

    fun trackAt(index: Int): Track

    fun setTracks(tracks: List<Track>, playWhenReady: Boolean)

    fun appendTracks(tracks: List<Track>)

    fun removeAt(index: Int)

    fun clear()

    fun seekTo(index: Int)

    fun skipToNext()

    fun prepare()

    fun play()

    fun stop()
}
