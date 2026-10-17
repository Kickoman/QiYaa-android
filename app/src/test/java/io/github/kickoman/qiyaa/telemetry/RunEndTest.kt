package io.github.kickoman.qiyaa.telemetry

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunEndTest {
    private val at = Instant.parse("2026-10-17T10:00:00Z")
    private val session = "026dd2da-999e-469f-ae7f-2a343875cd3e"
    private val counts = Counts(seconds = 840, tracks = 12, playMinutes = 40, jams = 1, errorsDropped = 3)
    private val marker = Marker("0.5.0", counts)
    private val report =
        CrashReport(
            session,
            "0.5.0",
            frames = listOf("io.github.kickoman.qiyaa.queue.QueueController.onFailure:291"),
            seconds = 5_423,
            exceptionType = "java.lang.IllegalStateException",
        )

    @Test
    fun `TEL-16 a crash report gives crash from it whatever Android says and with or without counts`() {
        val exits = listOf(null) + ExitReason.entries.map { ExitInfo(it, 11) }
        for (exit in exits) {
            for (saved in listOf(marker, null)) {
                assertEquals("$exit $saved", report.event(at), RunEnd.event(session, saved, report, exit, at))
            }
        }
    }

    @Test
    fun `TEL-16 a native crash Android reported gives crash with its signal, no frames, the saved seconds`() {
        assertEquals(
            Event.Crash(at, session, "0.5.0", "SIGSEGV", emptyList(), 840),
            RunEnd.event(session, marker, null, ExitInfo(ExitReason.NATIVE_CRASH, 11), at),
        )
    }

    @Test
    fun `TEL-16 an ANR or a Java crash without a report gives unclean_exit with the saved seconds`() {
        for (reason in listOf(ExitReason.ANR, ExitReason.CRASH)) {
            assertEquals(
                "$reason",
                Event.UncleanExit(at, session, "0.5.0", 840),
                RunEnd.event(session, marker, null, ExitInfo(reason), at),
            )
        }
    }

    @Test
    fun `TEL-16 any other end, or no exit info before Android 11, gives exit with the saved counts`() {
        for (exit in listOf(ExitInfo(ExitReason.OTHER), null)) {
            assertEquals(
                "$exit",
                Event.Exit(at, session, "0.5.0", counts),
                RunEnd.event(session, marker, null, exit, at),
            )
        }
    }

    @Test
    fun `TEL-16 a run that left neither a crash report nor counts gets no event`() {
        for (exit in listOf(null) + ExitReason.entries.map { ExitInfo(it, 6) }) {
            assertNull("$exit", RunEnd.event(session, null, null, exit, at))
        }
    }

    @Test
    fun `signals have their names and an unnamed one is SIGNAL_n`() {
        assertEquals("SIGSEGV", RunEnd.signalName(11))
        assertEquals("SIGABRT", RunEnd.signalName(6))
        assertEquals("SIGBUS", RunEnd.signalName(7))
        assertEquals("SIGFPE", RunEnd.signalName(8))
        assertEquals("SIGILL", RunEnd.signalName(4))
        assertEquals("SIGTRAP", RunEnd.signalName(5))
        assertEquals("SIGSYS", RunEnd.signalName(31))
        assertEquals("SIGNAL_40", RunEnd.signalName(40))
        assertEquals("SIGNAL_0", RunEnd.signalName(-3))
    }
}
