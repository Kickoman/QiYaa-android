package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.yandex.ResolvedUrl
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TrackUrlCacheTest {
    private var now = 0L
    private val signed = ArrayList<String>()
    private val cache =
        TrackUrlCache(clock = { now }, ttlMs = 1_000, capacity = 2) { id ->
            signed += id
            ResolvedUrl("https://storage/$id/${signed.size}", 320)
        }

    @Test
    fun `peek gives a fresh link without signing one`() = runBlocking {
        assertEquals(null, cache.peek("1"))
        cache.get("1")
        now = 999
        assertEquals("https://storage/1/1", cache.peek("1"))
        now = 1_000
        assertEquals(null, cache.peek("1"))
        assertEquals(listOf("1"), signed)
    }

    @Test
    fun `a link asked for again within its lifetime is not signed again`() = runBlocking {
        val first = cache.get("1")
        now = 999
        assertEquals(first, cache.get("1"))
        assertEquals(listOf("1"), signed)
    }

    @Test
    fun `a link older than its lifetime is signed again`() = runBlocking {
        cache.get("1")
        now = 1_000
        assertEquals("https://storage/1/2", cache.get("1").url)
        assertEquals(listOf("1", "1"), signed)
    }

    @Test
    fun `a link the storage rejected is signed again at once`() = runBlocking {
        cache.get("1")
        cache.invalidate("1")
        cache.get("1")
        assertEquals(listOf("1", "1"), signed)
    }

    @Test
    fun `each track has its own link`() = runBlocking {
        assertEquals("https://storage/1/1", cache.get("1").url)
        assertEquals("https://storage/2/2", cache.get("2").url)
        assertEquals("https://storage/1/1", cache.get("1").url)
    }

    @Test
    fun `the cache keeps only the most recently used links`() = runBlocking {
        cache.get("1")
        cache.get("2")
        cache.get("1")
        cache.get("3")
        cache.get("1")
        cache.get("2")
        assertEquals(listOf("1", "2", "3", "2"), signed)
    }

    @Test
    fun `a failed signing is not remembered`() = runBlocking {
        var calls = 0
        val failing =
            TrackUrlCache(clock = { now }) {
                if (++calls == 1) throw IOException("offline")
                ResolvedUrl("https://storage/x", 128)
            }
        try {
            failing.get("1")
            fail("expected IOException")
        } catch (ignored: IOException) {
            // The first signing fails on purpose.
        }
        assertEquals("https://storage/x", failing.get("1").url)
        assertEquals(2, calls)
    }
}
