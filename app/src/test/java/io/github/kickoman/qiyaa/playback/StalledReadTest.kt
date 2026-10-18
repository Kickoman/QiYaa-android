package io.github.kickoman.qiyaa.playback

import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalledReadTest {
    private val server = MockWebServer()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @After
    fun tearDown() {
        scheduler.shutdownNow()
        server.shutdown()
    }

    @Test
    fun `TR-09 an interrupt ends a stalled HTTP1 body read long before the read timeout`() {
        val interrupts =
            ReadInterrupts({ delayMs, check ->
                scheduler.schedule(Runnable(check), delayMs, TimeUnit.MILLISECONDS)
            })
        val stalled = StalledRead(client(Protocol.HTTP_1_1), interrupts)

        val failed = stalled.interruptAndAwait(TimeUnit.SECONDS.toMillis(5))

        assertNotNull(failed)
        assertFalse(failed is InterruptedIOException)
        assertTrue(stalled.call.isCanceled())
        assertTrue("took ${stalled.elapsedMs} ms", stalled.elapsedMs < TimeUnit.SECONDS.toMillis(5))
    }

    @Test
    fun `TR-09 an interrupt ends a stalled HTTP2 body read at once`() {
        val interrupts = ReadInterrupts({ _, _ -> })
        val stalled = StalledRead(client(Protocol.H2_PRIOR_KNOWLEDGE), interrupts)

        val failed = stalled.interruptAndAwait(TimeUnit.SECONDS.toMillis(5))

        assertTrue("$failed", failed is InterruptedIOException)
        assertFalse(stalled.call.isCanceled())
        assertTrue("took ${stalled.elapsedMs} ms", stalled.elapsedMs < TimeUnit.SECONDS.toMillis(1))
    }

    @Test
    fun `a stalled HTTP1 read ignores an interrupt by itself`() {
        val interrupts = ReadInterrupts({ _, _ -> })
        val stalled = StalledRead(client(Protocol.HTTP_1_1), interrupts)

        stalled.reader.interrupt()
        stalled.reader.join(1_000)

        assertTrue(stalled.reader.isAlive)
        stalled.call.cancel()
        stalled.reader.join(5_000)
        assertFalse(stalled.reader.isAlive)
    }

    private fun client(protocol: Protocol): OkHttpClient {
        server.protocols = listOf(protocol)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(BODY_BYTES)))
                .throttleBody(FIRST_BYTES.toLong(), 1, TimeUnit.HOURS),
        )
        server.start()
        return OkHttpClient.Builder()
            .protocols(listOf(protocol))
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private inner class StalledRead(client: OkHttpClient, interrupts: ReadInterrupts) {
        private val watch = interrupts.watch()
        private val calls = CallTracker(client, watch)
        private val firstBytesRead = CountDownLatch(1)

        @Volatile
        private var failure: Throwable? = null

        @Volatile
        private var endedAtMs = 0L
        private var interruptedAtMs = 0L
        lateinit var call: Call

        val elapsedMs: Long get() = endedAtMs - interruptedAtMs

        val reader =
            thread {
                try {
                    val response: Response =
                        watch.during(null) {
                            calls.newCall(Request.Builder().url(server.url("/")).build()).execute()
                        }
                    call = checkNotNull(calls.takeLast())
                    val body: InputStream = response.body.byteStream()
                    val buffer = ByteArray(FIRST_BYTES)
                    var read = 0
                    while (read < FIRST_BYTES) {
                        read += watch.during(call) { body.read(buffer, 0, FIRST_BYTES - read) }
                    }
                    firstBytesRead.countDown()
                    watch.during(call) { body.read(buffer) }
                } catch (failed: Throwable) {
                    failure = failed
                } finally {
                    endedAtMs = System.nanoTime() / NANOS_PER_MS
                }
            }

        init {
            assertTrue(firstBytesRead.await(5, TimeUnit.SECONDS))
            Thread.sleep(SETTLE_MS)
            assertTrue(reader.isAlive)
        }

        fun interruptAndAwait(timeoutMs: Long): Throwable? {
            interruptedAtMs = System.nanoTime() / NANOS_PER_MS
            reader.interrupt()
            reader.join(timeoutMs)
            assertFalse(reader.isAlive)
            return failure
        }
    }

    private companion object {
        const val BODY_BYTES = 1 shl 20
        const val FIRST_BYTES = 16_384
        const val READ_TIMEOUT_SECONDS = 20L
        const val SETTLE_MS = 200L
        const val NANOS_PER_MS = 1_000_000L
    }
}
