package io.github.kickoman.qiyaa.telemetry

import io.github.kickoman.qiyaa.support.TelemetryHarness
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.MACHINE
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.events
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.string
import io.github.kickoman.qiyaa.support.TelemetryHarness.Companion.types
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetrySessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null
    private val posted = ArrayList<String>()
    private val enabled = MutableStateFlow(true)
    private val onScreen = MutableStateFlow(false)
    private val playing = MutableStateFlow(false)
    private val online = MutableStateFlow(true)
    private lateinit var telemetry: TelemetryHarness

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test
    fun `TEL-05 with the setting on the crash handler is in place when start returns`() = runTest {
        session()
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashHandler)
    }

    @Test
    fun `TEL-03 the start goes out at the start`() = runTest {
        val session = session()
        runCurrent()
        assertEquals(listOf("start"), types(posted.single()))
        assertEquals(session.reporter!!.session, events(posted.single()).single().string("session"))
    }

    @Test
    fun `TEL-03 nothing goes out offline, and the network coming back sends`() = runTest {
        online.value = false
        session()
        runCurrent()
        assertEquals(0, posted.size)

        online.value = true
        runCurrent()
        assertEquals(listOf("start"), types(posted.single()))
    }

    @Test
    fun `TEL-03 in use the counts are saved every minute and waiting events go every 5th minute`() = runTest {
        val session = session()
        runCurrent()
        posted.clear()
        val id = session.reporter!!.session
        onScreen.value = true
        runCurrent()
        session.recordError(ErrorArea.API, "network")

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(60L, telemetry.store.readMarker(id)!!.counts.seconds)
        advanceTimeBy(3 * 60_000 + 59_999)
        runCurrent()
        assertEquals(0, posted.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("error"), types(posted.single()))
        assertEquals(300L, telemetry.store.readMarker(id)!!.counts.seconds)
    }

    @Test
    fun `TEL-03 leaving use saves the counts and sends`() = runTest {
        val session = session()
        runCurrent()
        posted.clear()
        onScreen.value = true
        runCurrent()
        advanceTimeBy(90_000)
        session.recordError(ErrorArea.API, "network")

        onScreen.value = false
        runCurrent()

        assertEquals(listOf("error"), types(posted.single()))
        assertEquals(90L, telemetry.store.readMarker(session.reporter!!.session)!!.counts.seconds)
    }

    @Test
    fun `TEL-03 playing with the screen off is in use and counts tracks, jams and play minutes`() = runTest {
        val session = session()
        runCurrent()
        posted.clear()
        playing.value = true
        session.countTrack()
        session.countTrack()
        session.countJam()
        session.recordError(ErrorArea.PLAYBACK, "network")

        advanceTimeBy(5 * 60_000)
        runCurrent()

        assertEquals(listOf("error"), types(posted.single()))
        assertEquals(
            Counts(seconds = 300, tracks = 2, playMinutes = 5, jams = 1),
            telemetry.store.readMarker(session.reporter!!.session)!!.counts,
        )
    }

    @Test
    fun `TEL-03 out of use nothing ticks and nothing is sent`() = runTest {
        val session = session()
        runCurrent()
        posted.clear()
        session.recordError(ErrorArea.API, "network")

        advanceTimeBy(60 * 60_000)
        runCurrent()

        assertEquals(0, posted.size)
        assertEquals(0L, telemetry.store.readMarker(session.reporter!!.session)!!.counts.seconds)
    }

    @Test
    fun `TEL-03 offline nothing is sent in use or on leaving, and no backoff is counted`() = runTest {
        online.value = false
        session()
        runCurrent()
        onScreen.value = true
        advanceTimeBy(10 * 60_000)
        onScreen.value = false
        runCurrent()
        assertEquals(0, posted.size)

        online.value = true
        runCurrent()

        assertEquals(listOf("start"), types(posted.single()))
    }

    @Test
    fun `TEL-02 off forgets the session, empties the folder and sends nothing more`() = runTest {
        val session = session()
        runCurrent()
        session.recordError(ErrorArea.API, "network")
        runCurrent()
        posted.clear()

        enabled.value = false
        runCurrent()

        assertNull(session.reporter)
        assertEquals(emptyList<String>(), telemetry.files())
        assertSame(original, Thread.getDefaultUncaughtExceptionHandler())
        onScreen.value = true
        session.recordError(ErrorArea.API, "network")
        advanceTimeBy(10 * 60_000)
        onScreen.value = false
        runCurrent()
        assertEquals(0, posted.size)
        assertEquals(emptyList<String>(), telemetry.files())
    }

    @Test
    fun `TEL-02 on again starts a new session with its own start`() = runTest {
        val session = session()
        runCurrent()
        val first = session.reporter!!.session
        enabled.value = false
        runCurrent()

        enabled.value = true
        runCurrent()

        val second = session.reporter!!.session
        assertNotEquals(first, second)
        val sent = events(posted.last())
        assertEquals(listOf("start" to second), sent.map { it.string("type") to it.string("session") })
    }

    @Test
    fun `TEL-02 an off and on the setting coalesces into on keep the session, which never went off`() =
        runTest {
            online.value = false
            val session = session()
            runCurrent()
            val old = session.reporter!!.session
            session.recordError(ErrorArea.API, "network")
            onScreen.value = true
            advanceTimeBy(60_000)
            runCurrent()
            val files = telemetry.files()
            assertTrue(files.toString(), files.any { it.startsWith(old) })

            enabled.value = false
            enabled.value = true
            runCurrent()

            assertEquals(old, session.reporter!!.session)
            assertEquals(files, telemetry.files())
            assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashHandler)
            online.value = true
            runCurrent()
            assertEquals(
                listOf("start" to old, "error" to old),
                events(posted.single()).map { it.string("type") to it.string("session") },
            )
        }

    @Test
    fun `TEL-02 an on while the off is forgetting waits for it, and nothing of the old session is left`() =
        runTest {
            online.value = false
            val session = session()
            runCurrent()
            val old = session.reporter!!.session
            session.recordError(ErrorArea.API, "network")
            onScreen.value = true
            advanceTimeBy(60_000)
            runCurrent()

            enabled.value = false
            yield()
            assertNull(session.reporter)
            assertTrue(telemetry.files().toString(), telemetry.files().any { it.startsWith(old) })
            enabled.value = true
            runCurrent()

            assertTrue(telemetry.files().toString(), telemetry.files().none { it.startsWith(old) })
            online.value = true
            runCurrent()
            val body = posted.single()
            assertFalse(body, body.contains(old))
            assertEquals(listOf("start"), types(body))
        }

    @Test
    fun `TEL-02 off at the start deletes what earlier runs left and starts nothing`() = runTest {
        enabled.value = false
        val directory = File(folder.root, "telemetry")
        directory.mkdirs()
        File(directory, "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53.crash").writeText("session=x\n")
        File(directory, "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53.json").writeText("{}")

        val session = session()
        runCurrent()

        assertEquals(emptyList<String>(), telemetry.files())
        assertNull(session.reporter)
        assertSame(original, Thread.getDefaultUncaughtExceptionHandler())
        assertEquals(0, posted.size)
    }

    @Test
    fun `a start that cannot read the phone sends nothing and does not crash`() = runTest {
        val logged = ArrayList<String>()
        val session =
            session(beginning = { throw IllegalStateException("no exit reasons") }, log = logged::add)
        runCurrent()
        session.recordError(ErrorArea.API, "network")
        onScreen.value = true
        advanceTimeBy(10 * 60_000)
        runCurrent()

        assertEquals(0, posted.size)
        assertEquals(listOf("Telemetry did not start: java.lang.IllegalStateException"), logged)
        enabled.value = false
        runCurrent()
        assertEquals(emptyList<String>(), telemetry.files())
    }

    private fun TestScope.session(
        beginning: suspend () -> Beginning = { Beginning(StartFields(), MACHINE) },
        log: (String) -> Unit = {},
    ): TelemetrySession {
        val clock = { testScheduler.currentTime }
        telemetry = TelemetryHarness(this, File(folder.root, "telemetry"), ::post, clock)
        return TelemetrySession(
            enabled = enabled,
            onScreen = onScreen,
            playing = playing,
            online = online,
            scope = backgroundScope,
            io = telemetry.io,
            store = telemetry.store,
            newReporter = { telemetry.reporter() },
            beginning = beginning,
            log = log,
        ).also { it.start() }
    }

    private fun post(body: String): Answer {
        posted += body
        return Answer.Accepted(204)
    }
}
