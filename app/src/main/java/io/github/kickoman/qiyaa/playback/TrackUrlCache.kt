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
    data class SignedLink(val link: ResolvedUrl, val quality: Quality)

    private data class Key(val trackId: String, val quality: Quality)

    private class Entry(val link: ResolvedUrl, val signedAtMs: Long)

    private val entries = leastRecentlyUsed<Key, Entry>()
    private val asked = leastRecentlyUsed<String, Quality>()
    private val opened = leastRecentlyUsed<String, Quality>()

    suspend fun get(trackId: String, quality: Quality? = null): SignedLink {
        val now = clock()
        val wanted: Quality
        synchronized(entries) {
            wanted = quality ?: this.quality()
            if (quality == null) asked[trackId] = wanted
            val cached = entries[Key(trackId, wanted)]
            if (cached != null && now - cached.signedAtMs < ttlMs) {
                if (quality == null) opened[trackId] = wanted
                return SignedLink(cached.link, wanted)
            }
        }
        val link = resolve(trackId, wanted)
        synchronized(entries) {
            entries[Key(trackId, wanted)] = Entry(link, now)
            if (quality == null) opened[trackId] = wanted
        }
        return SignedLink(link, wanted)
    }

    fun askedInOtherQuality(trackId: String): Boolean {
        synchronized(entries) {
            val last = asked[trackId] ?: return false
            return last != quality()
        }
    }

    fun peek(trackId: String): String? {
        val now = clock()
        synchronized(entries) {
            val latest = opened[trackId] ?: return null
            val cached = entries[Key(trackId, latest)] ?: return null
            return cached.link.url.takeIf { now - cached.signedAtMs < ttlMs }
        }
    }

    fun invalidate(trackId: String, url: String) {
        synchronized(entries) {
            for (signedIn in Quality.entries) {
                val key = Key(trackId, signedIn)
                if (entries[key]?.link?.url == url) entries.remove(key)
            }
        }
    }

    private fun <K, V> leastRecentlyUsed(): LinkedHashMap<K, V> =
        object : LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
        }

    companion object {
        const val TTL_MS = 15 * 60 * 1_000L
        const val CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
