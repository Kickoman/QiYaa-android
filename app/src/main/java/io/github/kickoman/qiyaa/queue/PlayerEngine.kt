package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track

interface PlayerEngine {
    val itemCount: Int
    val currentIndex: Int
    val positionMs: Long
    val isEnded: Boolean
    var shuffleEnabled: Boolean
    var repeatEnabled: Boolean

    fun hasNext(): Boolean

    fun hasPrevious(): Boolean

    fun playOrder(): List<Int>

    fun setTracks(tracks: List<Track>, playWhenReady: Boolean, startIndex: Int = 0, startPositionMs: Long = 0)

    fun appendTracks(tracks: List<Track>)

    fun insertTracks(index: Int, tracks: List<Track>)

    fun removeAt(index: Int)

    fun clear()

    fun seekTo(index: Int)

    fun seekToPosition(positionMs: Long)

    fun skipToNext()

    fun skipToPrevious()

    fun prepare()

    fun play()

    fun pause()

    fun stop()
}
