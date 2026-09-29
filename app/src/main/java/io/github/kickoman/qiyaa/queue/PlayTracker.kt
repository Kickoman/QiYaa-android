package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track

class PlayTracker(private val clock: () -> Long) {
    data class Closed(val track: Track, val playedSeconds: Double, val finished: Boolean)

    data class Change(val closed: Closed?, val started: Track?)

    private var current: Track? = null
    private var startPending = false
    private var playing = false
    private var open: Track? = null
    private var playedMs = 0L
    private var playingSince: Long? = null

    fun onItemChanged(track: Track?, transition: Transition, isPlaying: Boolean): Change {
        val closed = close(finished = transition == Transition.AUTO || transition == Transition.REPEAT)
        current = track
        startPending = track != null
        playing = isPlaying
        return Change(closed, startIfPlaying())
    }

    fun onEnded(): Change {
        val closed = close(finished = true)
        startPending = current != null
        return Change(closed, null)
    }

    fun onRestart(): Change {
        val closed = close(finished = false)
        startPending = current != null
        return Change(closed, startIfPlaying())
    }

    fun onPlayingChanged(isPlaying: Boolean): Track? {
        accumulate()
        playing = isPlaying
        playingSince = if (isPlaying && open != null) clock() else null
        return startIfPlaying()
    }

    fun closeAsSkip(): Closed? = close(finished = false)

    private fun startIfPlaying(): Track? {
        if (!playing || !startPending) return null
        startPending = false
        open = current
        playedMs = 0
        playingSince = clock()
        return current
    }

    private fun close(finished: Boolean): Closed? {
        val track = open ?: return null
        accumulate()
        open = null
        playingSince = null
        return Closed(track, playedMs / 1000.0, finished)
    }

    private fun accumulate() {
        val since = playingSince ?: return
        val now = clock()
        playedMs += now - since
        playingSince = if (playing && open != null) now else null
    }
}
