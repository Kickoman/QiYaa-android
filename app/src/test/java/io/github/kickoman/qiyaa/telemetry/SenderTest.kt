package io.github.kickoman.qiyaa.telemetry

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SenderTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `TEL-04 the answer decides what happens to the events`() {
        assertEquals(Answer.Accepted(204), Answer.of(204, null))
        assertEquals(Answer.Accepted(200), Answer.of(200, null))
        assertEquals(Answer.Dropped(400), Answer.of(400, null))
        assertEquals(Answer.Dropped(413), Answer.of(413, null))
        assertEquals(Answer.Failed(500), Answer.of(500, null))
        assertEquals(Answer.Failed(302), Answer.of(302, null))
        assertEquals(Answer.Failed(404), Answer.of(404, null))
        assertEquals(Answer.Failed(null), Answer.of(null, null))
    }

    @Test
    fun `TEL-04 429 waits Retry-After seconds, 60 without a usable header, at most an hour`() {
        assertEquals(Answer.RetryAfter(30), Answer.of(429, "30"))
        assertEquals(Answer.RetryAfter(30), Answer.of(429, " 30 "))
        assertEquals(Answer.RetryAfter(60), Answer.of(429, null))
        assertEquals(Answer.RetryAfter(60), Answer.of(429, "0"))
        assertEquals(Answer.RetryAfter(60), Answer.of(429, "-5"))
        assertEquals(Answer.RetryAfter(60), Answer.of(429, "Fri, 17 Oct 2026 10:00:00 GMT"))
        assertEquals(Answer.RetryAfter(3_600), Answer.of(429, "86400"))
    }

    @Test
    fun `TEL-04 failures wait 1 minute, doubling up to an hour`() {
        assertEquals(
            listOf(60L, 120L, 240L, 480L, 960L, 1_920L, 3_600L, 3_600L, 3_600L),
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 100).map(Backoff::seconds),
        )
    }

    @Test
    fun `a batch is a POST of application-json to the endpoint`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val body = """{"app":"android","machine":"97eaa8cc2fbe9a456389e6bd9d44a3fb","events":[]}"""

        val answer = Sender(OkHttpClient(), server.url("/api/telemetry").toString()).post(body)

        assertEquals(Answer.Accepted(204), answer)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/telemetry", request.path)
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals(body, request.body.readUtf8())
    }

    @Test
    fun `the Retry-After header reaches the answer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader(Sender.RETRY_AFTER, "30"))
        assertEquals(Answer.RetryAfter(30), Sender(OkHttpClient(), server.url("/").toString()).post("{}"))
    }

    @Test
    fun `no answer is a failure without a status`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(Answer.Failed(null), Sender(OkHttpClient(), server.url("/").toString()).post("{}"))
    }

    @Test
    fun `cancelling a post cancels its HTTP call at once`() = runBlocking {
        val release = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(204)
                }
            }
        val canceled = CountDownLatch(1)
        val client =
            OkHttpClient.Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) = canceled.countDown()
                    },
                )
                .build()
        val sender = Sender(client, server.url("/").toString())
        val post = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { sender.post("{}") }
        server.takeRequest(10, TimeUnit.SECONDS)!!

        withTimeout(1_000) {
            post.cancel()
            post.join()
        }

        assertTrue(canceled.await(1, TimeUnit.SECONDS))
        assertEquals(1L, release.count)
        release.countDown()
    }
}
