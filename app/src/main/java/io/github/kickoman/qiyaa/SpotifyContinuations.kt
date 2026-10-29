package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.queue.Continuation
import io.github.kickoman.qiyaa.queue.ContinuationKey
import io.github.kickoman.qiyaa.spotify.ContextPage
import io.github.kickoman.qiyaa.spotify.SpotifyLibrary
import io.github.kickoman.qiyaa.spotify.SpotifyTracks
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource

interface SpotifyCatalog {
    suspend fun autoplay(contextUri: String, recentTrackUris: List<String>): ContextPage

    suspend fun station(trackUri: String): ContextPage

    suspend fun nextPage(pageUrl: String): ContextPage
}

class SpotifyContinuations(private val catalog: SpotifyCatalog) {
    fun autoplay(contextUri: String): Continuation = Pages(ContinuationKey(AUTOPLAY, contextUri)) { started ->
        catalog.autoplay(contextUri, recentUris(started))
    }

    fun station(trackUri: String): Continuation =
        Pages(ContinuationKey(STATION, trackUri)) { catalog.station(trackUri) }

    fun autoplayFrom(track: Track): Continuation? =
        if (track.source == TrackSource.SPOTIFY) autoplay(SpotifyTracks.uriOf(track)) else null

    fun of(key: ContinuationKey): Continuation? {
        if (key.value.isEmpty()) return null
        return when (key.kind) {
            AUTOPLAY -> autoplay(key.value)
            STATION -> station(key.value)
            else -> null
        }
    }

    private inner class Pages(
        override val key: ContinuationKey,
        private val first: suspend (started: List<Track>) -> ContextPage,
    ) : Continuation {
        private var next = ""

        override suspend fun more(started: List<Track>): List<Track> {
            val page = if (next.isEmpty()) first(started) else catalog.nextPage(next)
            next = page.next
            return page.tracks
        }
    }

    companion object {
        const val AUTOPLAY = "spotify-autoplay"
        const val STATION = "spotify-station"
        const val RECENT_TRACKS = 50

        fun recentUris(started: List<Track>): List<String> {
            val spotify = started.filter { it.source == TrackSource.SPOTIFY }
            return spotify.takeLast(RECENT_TRACKS).map(SpotifyTracks::uriOf)
        }
    }
}

fun SpotifyLibrary.asCatalog(): SpotifyCatalog = object : SpotifyCatalog {
    override suspend fun autoplay(contextUri: String, recentTrackUris: List<String>): ContextPage =
        this@asCatalog.autoplay(contextUri, recentTrackUris)

    override suspend fun station(trackUri: String): ContextPage = this@asCatalog.station(trackUri)

    override suspend fun nextPage(pageUrl: String): ContextPage = this@asCatalog.nextPage(pageUrl)
}
