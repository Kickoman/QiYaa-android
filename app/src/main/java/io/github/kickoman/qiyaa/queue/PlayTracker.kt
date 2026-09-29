package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.yandex.Track

class PlayTracker {
    private var current: Track? = null
    private var startPending = false

    fun onItemChanged(track: Track?) {
        current = track
        startPending = track != null
    }

    fun onRestart() {
        startPending = current != null
    }

    fun onPlayingChanged(isPlaying: Boolean): Track? {
        if (!isPlaying || !startPending) return null
        startPending = false
        return current
    }
}
