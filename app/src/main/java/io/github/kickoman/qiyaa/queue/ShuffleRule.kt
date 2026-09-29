package io.github.kickoman.qiyaa.queue

class ShuffleRule {
    var wanted: Boolean = false
        private set

    fun playerModeFor(isWave: Boolean): Boolean = wanted && !isWave

    fun onPlayerChanged(enabled: Boolean, isWave: Boolean): Boolean {
        if (isWave) return false
        wanted = enabled
        return enabled
    }
}
