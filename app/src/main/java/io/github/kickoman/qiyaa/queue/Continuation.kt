package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.track.Track

interface Continuation {
    val key: ContinuationKey

    suspend fun more(started: List<Track>): List<Track>
}

data class ContinuationKey(val kind: String, val value: String)

data class SearchPick(val tracks: List<Track>, val name: String = "", val continuation: Continuation? = null)

class StartedTracks(tracks: List<Track> = emptyList()) {
    private val oldestFirst = ArrayDeque<Track>()

    init {
        tracks.forEach(::note)
    }

    val tracks: List<Track> get() = oldestFirst.toList()

    fun note(track: Track) {
        oldestFirst.removeAll { it.key == track.key }
        oldestFirst.addLast(track)
        if (oldestFirst.count { it.source == track.source } > MAX) {
            oldestFirst.removeAt(oldestFirst.indexOfFirst { it.source == track.source })
        }
    }

    fun clear() = oldestFirst.clear()

    companion object {
        const val MAX = 50
    }
}
