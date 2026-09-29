package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.SearchResult
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch

class LibraryMusicSource(private val library: Library) : MusicSource {
    override suspend fun startWave(seeds: List<String>): WaveBatch = library.startWave(seeds)

    override suspend fun moreWave(sessionId: String, queue: List<String>): WaveBatch =
        library.moreWave(sessionId, queue)

    override suspend fun search(text: String): SearchResult = library.search(text)

    override suspend fun artistTopTracks(artistId: String): List<Track> = library.artistTopTracks(artistId)

    override suspend fun albumTracks(albumId: String): List<Track> = library.albumTracks(albumId)

    override fun isLiked(trackId: String): Boolean = library.isLiked(trackId)

    override suspend fun setLiked(trackId: String, liked: Boolean) = library.setLiked(trackId, liked)

    override suspend fun dislike(trackId: String) = library.dislike(trackId)

    override suspend fun reportPlayStarted(track: Track, playId: String) {
        val account = library.account.value
        if (account.isValid) library.api.reportPlayStarted(account, track, playId)
    }
}
