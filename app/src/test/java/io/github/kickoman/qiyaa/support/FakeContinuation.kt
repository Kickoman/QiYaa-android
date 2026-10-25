package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.Continuation
import io.github.kickoman.qiyaa.queue.ContinuationKey
import io.github.kickoman.qiyaa.track.Track
import kotlinx.coroutines.CompletableDeferred

class FakeContinuation(override val key: ContinuationKey = ContinuationKey("fake", "spotify:playlist:p1")) :
    Continuation {
    val asked = ArrayList<List<String>>()
    var onMore: suspend (List<Track>) -> List<Track> = { error("unexpected more ${it.map(Track::id)}") }

    override suspend fun more(started: List<Track>): List<Track> {
        asked += started.map(Track::id)
        return onMore(started)
    }

    fun replies(vararg pages: List<Track>, then: CompletableDeferred<List<Track>> = CompletableDeferred()) {
        val left = ArrayDeque(pages.map { CompletableDeferred(it) } + then)
        onMore = { (left.removeFirstOrNull() ?: CompletableDeferred()).await() }
    }
}
