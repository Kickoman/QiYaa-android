package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.yandex.Quality
import io.github.kickoman.qiyaa.yandex.ResolvedUrl

class TrackUrlCache(
    private val clock: () -> Long,
    private val quality: () -> Quality = { Quality.STANDARD },
    private val ttlMs: Long = TTL_MS,
    private val capacity: Int = CAPACITY,
    private val resolve: suspend (String, Quality) -> ResolvedUrl,
) {
    private class Entry(val link: ResolvedUrl, val quality: Quality, val signedAtMs: Long)

    private val entries =
        object : LinkedHashMap<String, Entry>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > capacity
        }

    private val asked =
        object : LinkedHashMap<String, Quality>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Quality>): Boolean =
                size > capacity
        }

    /** [continuing]: the file is read on from the middle, so it keeps its quality (QUAL-08). */
    suspend fun get(trackId: String, continuing: Boolean = false): ResolvedUrl {
        val now = clock()
        val wanted: Quality
        synchronized(entries) {
            val cached = entries[trackId]
            wanted = if (continuing && cached != null) cached.quality else quality()
            asked[trackId] = wanted
            if (cached != null &&
                cached.quality == wanted &&
                now - cached.signedAtMs < ttlMs
            ) {
                return cached.link
            }
        }
        val link = resolve(trackId, wanted)
        synchronized(entries) { entries[trackId] = Entry(link, wanted, now) }
        return link
    }

    fun askedInOtherQuality(trackId: String): Boolean {
        synchronized(entries) {
            val last = asked[trackId] ?: return false
            return last != quality()
        }
    }

    /** The link already signed for [trackId] and still fresh, without signing one: for the jam's listeners. */
    fun peek(trackId: String): String? {
        val now = clock()
        synchronized(entries) {
            val cached = entries[trackId] ?: return null
            return cached.link.url.takeIf { now - cached.signedAtMs < ttlMs }
        }
    }

    /** The storage refused the link: the next [get] signs it again, in the same quality. */
    fun invalidate(trackId: String) {
        val expired = clock() - ttlMs
        synchronized(entries) {
            val cached = entries[trackId] ?: return
            entries[trackId] = Entry(cached.link, cached.quality, expired)
        }
    }

    companion object {
        const val TTL_MS = 15 * 60 * 1_000L
        const val CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
