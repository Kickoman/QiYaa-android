package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.yandex.ResolvedUrl

class TrackUrlCache(
    private val clock: () -> Long,
    private val ttlMs: Long = TTL_MS,
    private val capacity: Int = CAPACITY,
    private val resolve: suspend (String) -> ResolvedUrl,
) {
    private class Entry(val link: ResolvedUrl, val signedAtMs: Long)

    private val entries =
        object : LinkedHashMap<String, Entry>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > capacity
        }

    suspend fun get(trackId: String): ResolvedUrl {
        val now = clock()
        synchronized(entries) {
            val cached = entries[trackId]
            if (cached != null && now - cached.signedAtMs < ttlMs) return cached.link
        }
        val link = resolve(trackId)
        synchronized(entries) { entries[trackId] = Entry(link, now) }
        return link
    }

    /** The link already signed for [trackId] and still fresh, without signing one: for the jam's listeners. */
    fun peek(trackId: String): String? {
        val now = clock()
        synchronized(entries) {
            val cached = entries[trackId] ?: return null
            return cached.link.url.takeIf { now - cached.signedAtMs < ttlMs }
        }
    }

    fun invalidate(trackId: String) {
        synchronized(entries) { entries.remove(trackId) }
    }

    companion object {
        const val TTL_MS = 15 * 60 * 1_000L
        const val CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
