package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.SearchResult
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.WaveContext
import io.github.kickoman.qiyaa.yandex.WaveEvent

interface MusicSource {
    suspend fun startWave(seeds: List<String>): WaveBatch

    suspend fun moreWave(sessionId: String, queue: List<String>): WaveBatch

    suspend fun search(text: String): SearchResult

    suspend fun artistTopTracks(artistId: String): List<Track>

    suspend fun albumTracks(albumId: String): List<Track>

    fun isLiked(trackId: String): Boolean

    suspend fun setLiked(trackId: String, liked: Boolean)

    suspend fun dislike(trackId: String)

    suspend fun reportPlayStarted(track: Track, playId: String)

    suspend fun waveFeedback(context: WaveContext, event: WaveEvent, track: Track?, playedSeconds: Double)
}
