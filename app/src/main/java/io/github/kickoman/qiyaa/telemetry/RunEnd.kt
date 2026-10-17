package io.github.kickoman.qiyaa.telemetry

import java.time.Instant

enum class ExitReason { CRASH, NATIVE_CRASH, ANR, OTHER }

data class ExitInfo(val reason: ExitReason, val signal: Int = 0)

object RunEnd {
    private val SIGNALS =
        mapOf(
            4 to "SIGILL",
            5 to "SIGTRAP",
            6 to "SIGABRT",
            7 to "SIGBUS",
            8 to "SIGFPE",
            9 to "SIGKILL",
            11 to "SIGSEGV",
            13 to "SIGPIPE",
            15 to "SIGTERM",
            31 to "SIGSYS",
        )

    fun event(session: String, marker: Marker?, crash: CrashReport?, exit: ExitInfo?, at: Instant): Event? {
        if (crash != null) return crash.event(at)
        if (marker == null) return null
        val seconds = marker.counts.seconds
        return when (exit?.reason) {
            ExitReason.NATIVE_CRASH ->
                Event.Crash(at, session, marker.version, signalName(exit.signal), emptyList(), seconds)
            ExitReason.ANR, ExitReason.CRASH -> Event.UncleanExit(at, session, marker.version, seconds)
            ExitReason.OTHER, null -> Event.Exit(at, session, marker.version, marker.counts)
        }
    }

    fun signalName(signal: Int): String = SIGNALS[signal] ?: "SIGNAL_${signal.coerceAtLeast(0)}"
}
