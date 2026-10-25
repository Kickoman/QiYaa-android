package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AudioFile
import io.github.kickoman.qiyaa.support.FakeCdn
import io.github.kickoman.qiyaa.support.FakeCdn.Answer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyStreamTest {
    private val cdn = FakeCdn()
    private val client = OkHttpClient()
    private val relinks = AtomicInteger()
    private val problems = CopyOnWriteArrayList<SpotifyProblem>()
    private val logged = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        try {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        } finally {
            cdn.close()
        }
    }

    @Test
    fun `SPOT-13 the stream is the Ogg file after Spotify's 167-byte header, decrypted from any byte`() {
        val stream = stream(cdn.file(cdn.link("a")))
        assertEquals(cdn.ogg.size.toLong(), stream.open(0))
        val whole = stream.readAll()
        assertArrayEquals(cdn.ogg, whole)
        assertArrayEquals("OggS".toByteArray(), whole.copyOf(4))
        assertEquals(cdn.ogg.size - 1_000L, stream.open(1_000))
        assertArrayEquals(cdn.ogg.copyOfRange(1_000, cdn.ogg.size), stream.readAll())
        assertEquals(listOf("bytes=167-", "bytes=1167-"), cdn.ranges("a"))
        assertEquals(listOf(USER_AGENT), cdn.asked.map { it.userAgent }.distinct())
    }

    @Test
    fun `a bounded open asks for its range and gives exactly its bytes`() {
        val stream = stream(cdn.file(cdn.link("a")))
        assertEquals(500L, stream.open(1_000, 500))
        assertArrayEquals(cdn.ogg.copyOfRange(1_000, 1_500), stream.readAll())
        assertEquals(listOf("bytes=1167-1666"), cdn.ranges("a"))
    }

    @Test
    fun `SPOT-13 a CDN that ignores the range and answers 200 is read from the wanted byte`() {
        val stream = stream(cdn.file(cdn.link("a", Answer.Whole)))
        assertEquals(cdn.ogg.size - 5_000L, stream.open(5_000))
        assertArrayEquals(cdn.ogg.copyOfRange(5_000, cdn.ogg.size), stream.readAll())
        assertEquals(30L, stream.open(cdn.ogg.size - 30L))
        assertArrayEquals(cdn.ogg.copyOfRange(cdn.ogg.size - 30, cdn.ogg.size), stream.readAll())
    }

    @Test
    fun `the length is unknown when Content-Range has no total, and then the reply's end is the end`() {
        val stream = stream(cdn.file(cdn.link("a", Answer.WithoutTotal)))
        assertEquals(SpotifyStream.UNKNOWN, stream.open(100))
        assertArrayEquals(cdn.ogg.copyOfRange(100, cdn.ogg.size), stream.readAll())
    }

    @Test
    fun `an open at the end of a file of known length asks nothing and reads the end`() {
        val stream = stream(cdn.file(cdn.link("a")))
        stream.open(0)
        stream.close()
        assertEquals(0L, stream.open(cdn.ogg.size.toLong()))
        assertEquals(SpotifyStream.END, stream.read(ByteArray(16), 0, 16))
        assertEquals(1, cdn.asked.size)
    }

    @Test
    fun `a reply that ends before the end goes on from the byte it reached, on the same link`() {
        val stream = stream(cdn.file(cdn.link("a", Answer.Short(10_000))))
        assertEquals(cdn.ogg.size.toLong(), stream.open(0))
        assertArrayEquals(cdn.ogg, stream.readAll())
        val starts = cdn.ranges("a").map { it!!.removePrefix("bytes=").removeSuffix("-").toLong() }
        assertEquals((0 until starts.size).map { AudioFile.HEADER_SIZE + it * 10_000L }, starts)
        assertEquals(listOf("a"), cdn.links().distinct())
    }

    @Test
    fun `SPOT-14 a link that fails at its start hands over to the next one at the same byte`() {
        val stream = stream(cdn.file(cdn.link("a", Answer.Status(500)), cdn.link("b")))
        assertEquals(cdn.ogg.size - 2_000L, stream.open(2_000))
        assertArrayEquals(cdn.ogg.copyOfRange(2_000, cdn.ogg.size), stream.readAll())
        assertEquals(listOf("a", "b"), cdn.links())
        assertEquals(listOf("bytes=2167-"), cdn.ranges("b"))
    }

    @Test
    fun `SPOT-14 a link that breaks off midway hands over to the next one at the byte it reached`() {
        val stream = stream(cdn.file(cdn.link("a", Answer.Disconnect), cdn.link("b")))
        stream.open(0)
        assertArrayEquals(cdn.ogg, stream.readAll())
        assertEquals(listOf("a", "b"), cdn.links())
        val resumed = cdn.ranges("b").single()!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue("$resumed", resumed > AudioFile.HEADER_SIZE && resumed < cdn.plain.size)
        assertEquals(0, relinks.get())
    }

    @Test
    fun `SPOT-14 a hop to the next link comes after the failed read's watch, never inside it`() {
        val guard = RecordingGuard()
        val stream = stream(cdn.file(cdn.link("a", Answer.Disconnect), cdn.link("b")), guard = guard)
        stream.open(0)
        assertArrayEquals(cdn.ogg, stream.readAll())
        assertEquals(1, guard.deepest)
        assertEquals(listOf(null, "a", null, "b"), guard.watched())
    }

    @Test
    fun `SPOT-14 when every link failed and the last by the network, it is a network failure`() {
        val stream = stream(cdn.file(cdn.link("a", Answer.Status(503)), cdn.refusedLink()))
        val failed = assertThrows(SpotifyNetworkException::class.java) { stream.open(0) }
        assertTrue("${failed.cause}", failed.cause is ConnectException)
        assertEquals(emptyList<SpotifyProblem>(), problems)
        assertEquals(0, relinks.get())
    }

    @Test
    fun `SPOT-14 when every link failed and the last by its status, it is a track failure with it`() {
        val stream = stream(cdn.file(cdn.refusedLink(), cdn.link("b", Answer.Status(503))))
        val failed = assertThrows(SpotifyHttpException::class.java) { stream.open(0) }
        assertEquals(503, failed.status)
        assertEquals(listOf<SpotifyProblem>(SpotifyProblem.Http(503)), problems)
        val said = logged + failed.message.orEmpty()
        assertEquals(emptyList<String>(), said.filter { FakeCdn.TOKEN_QUERY in it || cdn.key.hex() in it })
    }

    @Test
    fun `SPOT-14 a 401 or 403 from the CDN is a failed link, not the account`() {
        val stream =
            stream(
                cdn.file(cdn.link("a", Answer.Status(401)), cdn.link("b", Answer.Status(403)), cdn.link("c")),
            )
        stream.open(0)
        assertArrayEquals(cdn.ogg, stream.readAll())
        assertEquals(listOf("a", "b", "c"), cdn.links())
        assertEquals(0, relinks.get())
    }

    @Test
    fun `SPOT-14 on Android all links answering 403 or 410 ask storage-resolve again, at the same byte`() {
        val expired = cdn.file(cdn.link("a", Answer.Status(403)), cdn.link("b", Answer.Status(410)))
        val stream = stream(expired, relinked = listOf(cdn.link("c")))
        assertEquals(cdn.ogg.size - 3_000L, stream.open(3_000))
        assertArrayEquals(cdn.ogg.copyOfRange(3_000, cdn.ogg.size), stream.readAll())
        assertEquals(1, relinks.get())
        assertEquals(listOf("a", "b", "c"), cdn.links())
        assertEquals(listOf("bytes=3167-"), cdn.ranges("c"))
    }

    @Test
    fun `SPOT-14 on Android a seek after the links expired goes on through the new links, and keeps them`() {
        val stream = stream(cdn.file(cdn.link("a")), relinked = listOf(cdn.link("c")))
        stream.open(0)
        stream.read(ByteArray(1_000), 0, 1_000)
        cdn.answer("a", Answer.Status(403))
        stream.open(20_000)
        assertArrayEquals(cdn.ogg.copyOfRange(20_000, cdn.ogg.size), stream.readAll())
        stream.open(40_000)
        assertArrayEquals(cdn.ogg.copyOfRange(40_000, cdn.ogg.size), stream.readAll())
        assertEquals(1, relinks.get())
        assertEquals(listOf("a", "a", "c", "c"), cdn.links())
    }

    @Test
    fun `SPOT-14 on Android new links that answer 403 too are a track failure, storage-resolve asked once`() {
        val stream =
            stream(
                cdn.file(cdn.link("a", Answer.Status(403))),
                relinked = listOf(cdn.link("b", Answer.Status(403))),
            )
        val failed = assertThrows(SpotifyHttpException::class.java) { stream.open(0) }
        assertEquals(403, failed.status)
        assertEquals(1, relinks.get())
        assertEquals(listOf("a", "b"), cdn.links())
    }

    @Test
    fun `SPOT-14 links that fail in other ways too ask no storage-resolve`() {
        val stream = stream(cdn.file(cdn.link("a", Answer.Status(403)), cdn.link("b", Answer.Status(500))))
        val failed = assertThrows(SpotifyHttpException::class.java) { stream.open(0) }
        assertEquals(500, failed.status)
        assertEquals(0, relinks.get())
    }

    @Test
    fun `SPOT-14 a read timeout is a failed link, not a cancel`() {
        val quick = client.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val file = cdn.file(cdn.link("a", Answer.Stall(8_192)), cdn.link("b"))
        val stream = stream(file, calls = quick)
        stream.open(0)
        assertArrayEquals(cdn.ogg, stream.readAll())
        assertEquals(listOf("a", "b"), cdn.links())
    }

    @Test
    fun `TR-09 a canceled call is not a failed link, so no next link and no storage-resolve`() {
        val calls = RecordingCalls(client)
        val stream = stream(cdn.file(cdn.link("a", Answer.Stall(8_192)), cdn.link("b")), calls = calls)
        val firstBytes = CountDownLatch(1)
        var failure: Throwable? = null
        val reader =
            thread {
                try {
                    stream.open(0)
                    stream.read(ByteArray(1_000), 0, 1_000)
                    firstBytes.countDown()
                    stream.readAll()
                } catch (failed: Throwable) {
                    failure = failed
                }
            }
        assertTrue(firstBytes.await(5, TimeUnit.SECONDS))
        Thread.sleep(SETTLE_MS)
        calls.last!!.cancel()
        reader.join(5_000)
        assertFalse(reader.isAlive)
        assertTrue("$failure", failure is IOException && failure !is SpotifyException)
        assertEquals(listOf("a"), cdn.links())
        assertEquals(0, relinks.get())
    }

    @Test
    fun `TR-09 an open or a read on an interrupted thread is not a failed link either`() {
        val stream = stream(cdn.file(cdn.link("a"), cdn.link("b")), guard = InterruptCheck)
        stream.open(0)
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { stream.read(ByteArray(16), 0, 16) }
            assertThrows(InterruptedIOException::class.java) { stream.open(100) }
        } finally {
            Thread.interrupted()
        }
        assertEquals(listOf("a"), cdn.links())
        assertEquals(0, relinks.get())
    }

    private fun stream(
        file: SpotifyFile,
        relinked: List<String> = emptyList(),
        guard: ReadGuard = ReadGuard.NONE,
        calls: Call.Factory = client,
    ) = SpotifyStream(
        calls = calls,
        file = file,
        userAgent = USER_AGENT,
        relink = {
            relinks.incrementAndGet()
            relinked
        },
        guard = guard,
        report = { problems += it },
        log = { logged += it },
    )

    private fun SpotifyStream.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count == SpotifyStream.END) return out.toByteArray()
            out.write(buffer, 0, count)
        }
    }

    private class RecordingGuard : ReadGuard {
        private val links = ArrayList<String?>()
        private var depth = 0
        var deepest = 0

        override fun <T> during(call: Call?, block: () -> T): T {
            depth++
            deepest = maxOf(deepest, depth)
            links += call?.request()?.url?.pathSegments?.first()
            try {
                return block()
            } finally {
                depth--
            }
        }

        fun watched(): List<String?> = links.filterIndexed { i, link -> i == 0 || links[i - 1] != link }
    }

    private object InterruptCheck : ReadGuard {
        override fun <T> during(call: Call?, block: () -> T): T {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("the load was canceled")
            return block()
        }
    }

    private class RecordingCalls(private val client: OkHttpClient) : Call.Factory {
        @Volatile
        var last: Call? = null

        override fun newCall(request: Request): Call = client.newCall(request).also { last = it }
    }

    private companion object {
        const val USER_AGENT = "Spotify/124200290 Linux/0 (QiYaa 0.8.0)"
        const val SETTLE_MS = 200L
    }
}
