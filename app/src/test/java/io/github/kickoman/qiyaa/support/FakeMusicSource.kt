package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.MusicSource
import io.github.kickoman.qiyaa.yandex.SearchResult
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.WaveContext
import io.github.kickoman.qiyaa.yandex.WaveEvent

class FakeMusicSource : MusicSource {
    val calls = ArrayList<String>()
    val liked = HashSet<String>()
    val reports = ArrayList<Pair<String, String>>()
    val feedback = ArrayList<String>()

    var onStartWave: suspend (List<String>) -> WaveBatch = { error("unexpected startWave $it") }
    var onMoreWave: suspend (
        String,
        List<String>,
    ) -> WaveBatch = { id, _ -> error("unexpected moreWave $id") }
    var onSearch: suspend (String) -> SearchResult = { error("unexpected search $it") }
    var onArtistTopTracks: suspend (String) -> List<Track> = { error("unexpected artistTopTracks $it") }
    var onAlbumTracks: suspend (String) -> List<Track> = { error("unexpected albumTracks $it") }

    override suspend fun startWave(seeds: List<String>): WaveBatch {
        calls += "startWave $seeds"
        return onStartWave(seeds)
    }

    override suspend fun moreWave(sessionId: String, queue: List<String>): WaveBatch {
        calls += "moreWave $sessionId $queue"
        return onMoreWave(sessionId, queue)
    }

    override suspend fun search(text: String): SearchResult {
        calls += "search $text"
        return onSearch(text)
    }

    override suspend fun artistTopTracks(artistId: String): List<Track> {
        calls += "artistTopTracks $artistId"
        return onArtistTopTracks(artistId)
    }

    override suspend fun albumTracks(albumId: String): List<Track> {
        calls += "albumTracks $albumId"
        return onAlbumTracks(albumId)
    }

    override fun isLiked(trackId: String): Boolean = trackId in liked

    override suspend fun setLiked(trackId: String, liked: Boolean) {
        calls += "setLiked $trackId $liked"
        if (liked) this.liked += trackId else this.liked -= trackId
    }

    override suspend fun dislike(trackId: String) {
        calls += "dislike $trackId"
        liked -= trackId
    }

    override suspend fun waveFeedback(
        context: WaveContext,
        event: WaveEvent,
        track: Track?,
        playedSeconds: Double,
    ) {
        val where = "${context.sessionId}/${context.stationId}/${context.batchId}"
        feedback += "${event.wireName} $where ${track?.id ?: "-"} ${Math.round(playedSeconds * 10) / 10.0}"
    }

    override suspend fun reportPlayStarted(track: Track, playId: String) {
        reports += track.id to playId
    }
}
