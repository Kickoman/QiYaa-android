package io.github.kickoman.qiyaa.queue

class WaveModeRule {
    var wanted: Boolean = false
        private set

    fun playerModeFor(locked: Boolean): Boolean = wanted && !locked

    fun onUserChanged(enabled: Boolean, locked: Boolean): Boolean {
        if (locked) return false
        wanted = enabled
        return enabled
    }
}
