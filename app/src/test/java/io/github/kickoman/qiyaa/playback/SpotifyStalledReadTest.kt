package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.spotify.SpotifyException
import io.github.kickoman.qiyaa.spotify.SpotifyFile
import io.github.kickoman.qiyaa.spotify.SpotifyStream
import io.github.kickoman.qiyaa.support.FakeCdn
import io.github.kickoman.qiyaa.support.FakeCdn.Answer
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyStalledReadTest {
    private val cdn = FakeCdn(oggBytes = 1 shl 20)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val interrupts =
        ReadInterrupts({ delayMs, check ->
            scheduler.schedule(Runnable(check), delayMs, TimeUnit.MILLISECONDS)
        })
    private val client = OkHttpClient.Builder().readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val relinks = AtomicInteger()

    @After
    fun tearDown() {
        try {
            scheduler.shutdownNow()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        } finally {
            cdn.close()
        }
    }

    @Test
    fun `TR-09 an interrupt ends a stalled read of the first link long before the read timeout`() {
        val stalled = StalledStream(cdn.file(cdn.link("a", Answer.Stall(FIRST_BYTES)), cdn.link("b")))

        val failed = stalled.interruptAndAwait()

        assertTrue("$failed", failed is IOException && failed !is SpotifyException)
        assertTrue(stalled.calls.takeLast()!!.isCanceled())
        assertTrue("took ${stalled.elapsedMs} ms", stalled.elapsedMs < TimeUnit.SECONDS.toMillis(5))
        assertEquals(listOf("a"), cdn.links())
        assertEquals(0, relinks.get())
    }

    @Test
    fun `TR-09 SPOT-14 after a hop from a broken read, a stalled read of the next link ends as fast`() {
        val file = cdn.file(cdn.link("a", Answer.Disconnect), cdn.link("b", Answer.Stall(FIRST_BYTES)))
        val stalled = StalledStream(file)

        val failed = stalled.interruptAndAwait()

        assertTrue("$failed", failed is IOException && failed !is SpotifyException)
        assertTrue(stalled.calls.takeLast()!!.isCanceled())
        assertTrue("took ${stalled.elapsedMs} ms", stalled.elapsedMs < TimeUnit.SECONDS.toMillis(5))
        assertEquals(listOf("a", "b"), cdn.links())
        assertEquals(0, relinks.get())
    }

    private inner class StalledStream(file: SpotifyFile) {
        private val watch = interrupts.watch()
        val calls = CallTracker(client, watch)
        private val stream =
            SpotifyStream(
                calls = calls,
                file = file,
                userAgent = "QiYaa test",
                relink = {
                    relinks.incrementAndGet()
                    emptyList()
                },
                guard = WatchGuard(watch),
            )
        private val read = AtomicLong()

        @Volatile
        private var failure: Throwable? = null

        @Volatile
        private var endedAtMs = 0L
        private var interruptedAtMs = 0L

        val elapsedMs: Long get() = endedAtMs - interruptedAtMs

        val reader =
            thread {
                try {
                    stream.open(0)
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = stream.read(buffer, 0, buffer.size)
                        if (count == SpotifyStream.END) break
                        read.addAndGet(count.toLong())
                    }
                } catch (failed: Throwable) {
                    failure = failed
                } finally {
                    endedAtMs = System.nanoTime() / NANOS_PER_MS
                }
            }

        init {
            awaitStall()
            assertTrue(reader.isAlive)
        }

        fun interruptAndAwait(): Throwable? {
            interruptedAtMs = System.nanoTime() / NANOS_PER_MS
            reader.interrupt()
            reader.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(reader.isAlive)
            return failure
        }

        private fun awaitStall() {
            val deadline = System.nanoTime() / NANOS_PER_MS + TimeUnit.SECONDS.toMillis(5)
            var last = -1L
            var steadySinceMs = 0L
            while (System.nanoTime() / NANOS_PER_MS < deadline) {
                val now = System.nanoTime() / NANOS_PER_MS
                val count = read.get()
                if (count != last) {
                    last = count
                    steadySinceMs = now
                } else if (count > 0 && now - steadySinceMs >= SETTLE_MS) {
                    return
                }
                Thread.sleep(POLL_MS)
            }
            throw AssertionError("the read did not stall: ${read.get()} bytes, ${cdn.links()}")
        }
    }

    private companion object {
        const val FIRST_BYTES = 16_384L
        const val BUFFER_BYTES = 4_096
        const val READ_TIMEOUT_SECONDS = 20L
        const val SETTLE_MS = 300L
        const val POLL_MS = 20L
        const val NANOS_PER_MS = 1_000_000L
    }
}
