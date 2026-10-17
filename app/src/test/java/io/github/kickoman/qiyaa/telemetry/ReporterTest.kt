package io.github.kickoman.qiyaa.telemetry

import io.github.kickoman.qiyaa.support.TelemetryHarness
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.MACHINE
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.events
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.sender
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.session
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.string
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.types
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        server.start()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        server.shutdown()
    }

    @Test
    fun `TEL-04 204 empties the queue and sends the next batch at once`() = runTest {
        val telemetry = harness()
        val reporter = telemetry.reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        repeat(120) { reporter.recordFeature(FeatureName.VIS, "v$it") }
        repeat(3) { answer(204) }

        assertEquals(Answer.Accepted(204), reporter.send())

        assertEquals(listOf(50, 50, 21), List(3) { events(body()).size })
        assertEquals(0, reporter.queued)
        runCurrent()
        assertEquals(listOf("${reporter.session}.json"), telemetry.files())
        assertEquals(List(3) { "HTTP 204, ${listOf(50, 50, 21)[it]} events sent" }, telemetry.logged)
    }

    @Test
    fun `TEL-04 a failure keeps the events and waits 1 minute, then 2 and so on up to an hour`() = runTest {
        val telemetry = harness()
        val reporter = telemetry.reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        answer(500)
        assertEquals(Answer.Failed(500), reporter.send())
        for ((i, waitSeconds) in listOf(60L, 120, 240, 480, 960, 1_920, 3_600, 3_600).withIndex()) {
            telemetry.uptimeMs += waitSeconds * 1_000 - 1
            assertNull("$waitSeconds s", reporter.send())
            assertEquals("$waitSeconds s", i + 1, server.requestCount)
            telemetry.uptimeMs += 1
            answer(500)
            assertEquals("$waitSeconds s", Answer.Failed(500), reporter.send())
        }
        assertEquals(1, reporter.queued)
        assertEquals("HTTP 500, 1 events kept, next try in 3600 s", telemetry.logged.last())
    }

    @Test
    fun `TEL-04 429 waits the Retry-After seconds`() = runTest {
        val telemetry = harness()
        val reporter = telemetry.reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        server.enqueue(MockResponse().setResponseCode(429).setHeader(Sender.RETRY_AFTER, "30"))
        assertEquals(Answer.RetryAfter(30), reporter.send())

        telemetry.uptimeMs = 29_999
        assertNull(reporter.send())
        assertEquals(1, server.requestCount)
        telemetry.uptimeMs = 30_000
        answer(204)
        assertEquals(Answer.Accepted(204), reporter.send())
        assertEquals(0, reporter.queued)
    }

    @Test
    fun `TEL-04 400 and 413 drop the batch and do not send the next one at once`() = runTest {
        val telemetry = harness()
        val reporter = telemetry.reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        repeat(120) { reporter.recordFeature(FeatureName.VIS, "v$it") }

        answer(400)
        assertEquals(Answer.Dropped(400), reporter.send())
        assertEquals(1, server.requestCount)
        assertEquals(71, reporter.queued)
        answer(413)
        assertEquals(Answer.Dropped(413), reporter.send())
        assertEquals(21, reporter.queued)
    }

    @Test
    fun `TEL-04 above 500 waiting events the oldest are dropped`() = runTest {
        val reporter = harness().reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        repeat(501) { reporter.recordFeature(FeatureName.VIS, "v$it") }

        assertEquals(500, reporter.queued)
        answer(204)
        answer(400)
        reporter.send()
        assertEquals("v1", events(body()).first().string("value"))
    }

    @Test
    fun `TEL-04 a batch holds at most 50 events and 60 KiB`() = runTest {
        val telemetry = harness()
        val longFrame = "io.github.kickoman.qiyaa." + "x".repeat(170) + ".run:1234567"
        repeat(12) {
            telemetry.writeCrash(CrashReport(session(it), "0.5.0", frames = List(32) { longFrame }))
        }
        val reporter = telemetry.reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        repeat(60) { reporter.recordFeature(FeatureName.VIS, "v$it") }
        repeat(5) { answer(204) }

        reporter.send()

        val batches = List(server.requestCount) { server.takeRequest() }
        assertTrue(batches.all { it.bodySize <= Reporter.MAX_BATCH_BYTES })
        val sizes = batches.map { events(it.body.readUtf8()).size }
        assertTrue("$sizes", sizes.all { it <= Reporter.MAX_BATCH_EVENTS })
        assertTrue("$sizes", sizes.first() < 12)
        assertEquals(12 + 1 + 60, sizes.sum())
    }

    @Test
    fun `TEL-07 errors after the 50th are only counted in errorsDropped`() = runTest {
        val telemetry = harness()
        val reporter = telemetry.reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        repeat(60) { reporter.recordError(ErrorArea.API, "http", 502) }

        assertEquals(1 + Reporter.MAX_ERRORS_PER_SESSION, reporter.queued)
        reporter.saveMarker()
        runCurrent()
        assertEquals(10L, telemetry.store.readMarker(reporter.session)!!.counts.errorsDropped)
    }

    @Test
    fun `TEL-05 TEL-16 earlier runs' events and ends come before this run's start, and their files go`() =
        runTest {
            val telemetry = harness()
            val crashed = "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53"
            val ended = "33691813-d7ab-47ec-b621-747be6192eee"
            telemetry.writeCrash(CrashReport(crashed, "0.5.8", frames = listOf("a.B.m:1"), seconds = 9))
            telemetry.store.writeMarker(crashed, Marker("0.5.8"))
            telemetry.store.writeQueue(ended, listOf(error(ended, "network"), error(ended, "auth")))
            telemetry.store.writeMarker(ended, Marker("0.5.9", Counts(6_120, 23, 87, 1, 0)))
            age(telemetry.directory, crashed, 1_000_000)
            age(telemetry.directory, ended, 2_000_000)
            val reporter = telemetry.reporter()
            reporter.recordError(ErrorArea.JAM, "server-full")

            reporter.begin(StartFields(), MACHINE, mapOf(crashed to ExitInfo(ExitReason.ANR)))

            assertEquals(listOf("${reporter.session}.json", "${reporter.session}.queue"), telemetry.files())
            answer(204)
            reporter.send()
            val sent = events(body())
            assertEquals(
                listOf(
                    "crash" to crashed,
                    "error" to ended,
                    "error" to ended,
                    "exit" to ended,
                    "start" to reporter.session,
                    "error" to reporter.session,
                ),
                sent.map { it.string("type") to it.string("session") },
            )
            assertEquals("0.5.9", sent[3].string("version"))
            assertEquals("6120", sent[3].string("seconds"))
        }

    @Test
    fun `an error recorded before begin comes after this run's start`() = runTest {
        val reporter = harness().reporter()
        reporter.recordError(ErrorArea.API, "network")
        assertEquals(1, reporter.queued)
        assertNull(reporter.send())

        reporter.begin(StartFields(), MACHINE, emptyMap())
        answer(204)
        reporter.send()

        assertEquals(listOf("start", "error"), types(body()))
    }

    @Test
    fun `TEL-02 forget posts nothing more, empties the folder and restores the crash handler`() = runTest {
        val telemetry = harness()
        val reporter = telemetry.reporter()
        reporter.install()
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashHandler)
        reporter.begin(StartFields(), MACHINE, emptyMap())
        reporter.recordError(ErrorArea.API, "network")
        runCurrent()

        reporter.forget()

        assertEquals(emptyList<String>(), telemetry.files())
        assertSame(original, Thread.getDefaultUncaughtExceptionHandler())
        reporter.recordError(ErrorArea.API, "network")
        reporter.saveMarker()
        assertNull(reporter.send())
        runCurrent()
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), telemetry.files())
        assertNotEquals(reporter.session, telemetry.reporter().session)
    }

    @Test
    fun `a send while a post is in flight returns at once and makes no second post`() = runTest {
        val release = CountDownLatch(1)
        hold(release, then = 204)
        val reporter = harness().reporter()
        reporter.begin(StartFields(), MACHINE, emptyMap())
        val first = backgroundScope.async { reporter.send() }
        runCurrent()
        server.takeRequest(10, TimeUnit.SECONDS)!!

        assertNull(reporter.send())

        release.countDown()
        assertEquals(Answer.Accepted(204), first.await())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `TEL-04 dropping above 500 spares the batch in flight, and its 204 removes exactly what it posted`() =
        runTest {
            val release = CountDownLatch(1)
            hold(release, then = 503)
            val reporter = harness().reporter()
            reporter.begin(StartFields(), MACHINE, emptyMap())
            repeat(49) { reporter.recordFeature(FeatureName.VIS, "f$it") }
            val first = backgroundScope.async { reporter.send() }
            runCurrent()
            val posted = events(server.takeRequest(10, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(50, posted.size)

            repeat(510) { reporter.recordFeature(FeatureName.VIS, "g$it") }
            assertEquals(500, reporter.queued)
            release.countDown()

            assertEquals(Answer.Failed(503), first.await())
            assertEquals(450, reporter.queued)
            val next = events(server.takeRequest(10, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals((60 until 110).map { "g$it" }, next.map { it.string("value") })
        }

    @Test
    fun `TEL-02 forget during a post and a pending write cancels the post and leaves the folder empty`() =
        runTest {
            val release = CountDownLatch(1)
            hold(release, then = 204)
            val canceled = CountDownLatch(1)
            val client =
                OkHttpClient.Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun canceled(call: Call) = canceled.countDown()
                        },
                    )
                    .build()
            val telemetry = harness(client)
            val reporter = telemetry.reporter()
            reporter.install()
            reporter.begin(StartFields(), MACHINE, emptyMap())
            val sending = backgroundScope.async { reporter.send() }
            runCurrent()
            server.takeRequest(10, TimeUnit.SECONDS)!!
            reporter.recordError(ErrorArea.API, "network")
            reporter.saveMarker()

            reporter.forget()

            assertEquals(1L, release.count)
            assertTrue(canceled.await(10, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), telemetry.files())
            assertSame(original, Thread.getDefaultUncaughtExceptionHandler())
            release.countDown()
            assertNull(sending.await())
            advanceUntilIdle()
            assertEquals(emptyList<String>(), telemetry.files())
            assertEquals(1, server.requestCount)
        }

    private fun TestScope.harness(client: OkHttpClient = OkHttpClient()) =
        TelemetryHarness(this, File(folder.root, "telemetry"), server.sender(client))

    private fun answer(status: Int) {
        server.enqueue(MockResponse().setResponseCode(status))
    }

    private fun body(): String = server.takeRequest(10, TimeUnit.SECONDS)!!.body.readUtf8()

    private fun hold(release: CountDownLatch, then: Int) {
        val first = AtomicBoolean(true)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!first.getAndSet(false)) return MockResponse().setResponseCode(then)
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(204)
                }
            }
    }

    private fun error(session: String, kind: String): String {
        val event = Event.Error(TelemetryHarness.START, session, "0.5.9", ErrorArea.PLAYBACK, kind)
        return EventCodec.encode(event)!!.toString()
    }

    private fun age(directory: File, session: String, modified: Long) {
        directory.listFiles().orEmpty().filter {
            it.name.startsWith(session)
        }.forEach { it.setLastModified(modified) }
    }
}
