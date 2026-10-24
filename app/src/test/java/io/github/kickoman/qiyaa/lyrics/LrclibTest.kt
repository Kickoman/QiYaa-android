package io.github.kickoman.qiyaa.lyrics

import io.github.kickoman.qiyaa.support.Spec
import io.github.kickoman.qiyaa.track.Track
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LrclibTest {
    private val server = MockWebServer()
    private lateinit var lrclib: Lrclib

    @Before
    fun setUp() {
        server.start()
        lrclib =
            Lrclib(OkHttpClient(), "1.2.3", server.url("/").toString().removeSuffix("/"), retryDelayMs = 1)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `LYR-04 the search names the title and the first artist and says who asks`() {
        server.enqueue(fixture("several-durations"))
        val answer = ask(Track("1", "Paper Boats", listOf("The Example Band", "Guest"), durationMs = 180_000))
        val request = server.takeRequest()
        assertEquals("/api/search", request.requestUrl!!.encodedPath)
        assertEquals("Paper Boats", request.requestUrl!!.queryParameter("track_name"))
        assertEquals("The Example Band", request.requestUrl!!.queryParameter("artist_name"))
        assertEquals(
            "QiYaa/1.2.3 (https://github.com/Kickoman/QiYaa-android)",
            request.getHeader("User-Agent"),
        )
        assertTrue(answer is LyricsAnswer.Text && answer.from == LyricsFrom.THIRD_PARTY)
    }

    @Test
    fun `LYR-04 no request goes out for a track without a title or an artist`() {
        assertEquals(LyricsAnswer.Missing(), ask(Track("1", "Song")))
        assertEquals(LyricsAnswer.Missing(), ask(Track("1", " ", listOf("Band"))))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `LYR-04 a 5xx is tried again once, and the second answer counts`() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(fixture("plain-only"))
        val answer = ask(Track("1", "Song", listOf("Band")))
        assertEquals(2, server.requestCount)
        assertTrue(answer is LyricsAnswer.Text)
    }

    @Test
    fun `LYR-04 a 503 after the retry is nothing with a server error, then the title without its version`() {
        server.enqueue(fixture("503-server-overloaded"))
        server.enqueue(fixture("503-server-overloaded"))
        server.enqueue(fixture("empty"))
        val answer = ask(Track("1", "Song (Live)", listOf("Band")))
        assertEquals(LyricsAnswer.Missing(serverError = true), answer)
        val titles = List(3) { server.takeRequest().title() }
        assertEquals(listOf("Song (Live)", "Song (Live)", "Song"), titles)
    }

    @Test
    fun `LYR-04 another 5xx after the retry is a failure`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(LyricsAnswer.Failed, ask(Track("1", "Song", listOf("Band"))))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `LYR-04 a network failure after the retry is a failure`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(LyricsAnswer.Failed, ask(Track("1", "Song", listOf("Band"))))
    }

    @Test
    fun `LYR-04 nothing found asks once more without the version, and a 4xx is nothing`() {
        server.enqueue(fixture("empty"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(LyricsAnswer.Missing(), ask(Track("1", "Song [Remastered]", listOf("Band"))))
        assertEquals(listOf("Song [Remastered]", "Song"), List(2) { server.takeRequest().title() })
    }

    @Test
    fun `LYR-04 an instrumental record says the track has no words`() {
        server.enqueue(fixture("instrumental"))
        assertEquals(LyricsAnswer.Instrumental, ask(Track("1", "Song", listOf("Band"), durationMs = 276_000)))
    }

    @Test
    fun `LYR-11 a reply over 2 MiB is a failure, with no retry`() {
        val big = "[" + "\"x\",".repeat((Lrclib.MAX_REPLY_BYTES / 4 + 1).toInt()) + "\"x\"]"
        server.enqueue(MockResponse().setBody(big))
        assertEquals(LyricsAnswer.Failed, ask(Track("1", "Song", listOf("Band"))))
        assertEquals(1, server.requestCount)
    }

    private fun ask(track: Track): LyricsAnswer = runBlocking { lrclib.lyrics(track) }

    private fun fixture(case: String): MockResponse = Spec.fixture("search", case, Spec.LRCLIB).response()

    private fun RecordedRequest.title(): String? = requestUrl!!.queryParameter("track_name")
}
