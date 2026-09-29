package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.SearchResult
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch

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
}
