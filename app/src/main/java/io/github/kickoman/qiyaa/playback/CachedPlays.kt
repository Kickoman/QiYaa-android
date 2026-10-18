package io.github.kickoman.qiyaa.playback

import java.security.SecureRandom

class CachedPlays(private val perUri: Int = PLAYS_PER_URI, private val process: String = processTag()) {
    private var issued = 0L
    private val uris = HashMap<String, String>()

    @Synchronized
    fun newKey(): String = "$KEY_PREFIX$process-${++issued}"

    @Synchronized
    fun opened(key: String, uri: String) {
        uris.putIfAbsent(key, uri)
    }

    @Synchronized
    fun toDrop(present: Set<String>, keep: List<String>): Set<String> {
        val kept = keep.toSet().flatMapTo(HashSet()) { uri -> newest(uri) }
        val dropped = present.filterTo(HashSet()) { it !in kept }
        uris.keys.removeAll(dropped)
        return dropped
    }

    private fun newest(uri: String): List<String> =
        uris.filterValues { it == uri }.keys.sortedByDescending(::numberOf).take(perUri)

    private fun numberOf(key: String): Long = key.substringAfterLast('-').toLong()

    companion object {
        const val PLAYS_PER_URI = 2
        const val KEY_PREFIX = "play-"
        private const val PROCESS_TAG_DIGITS = 8

        private fun processTag(): String =
            Integer.toHexString(SecureRandom().nextInt()).padStart(PROCESS_TAG_DIGITS, '0')
    }
}
