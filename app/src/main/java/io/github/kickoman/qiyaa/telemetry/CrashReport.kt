package io.github.kickoman.qiyaa.telemetry

import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

data class CrashReport(
    val session: String,
    val version: String,
    val signal: String = TERMINATE,
    val frames: List<String> = emptyList(),
    val seconds: Long = 0,
    val exceptionType: String? = null,
) {
    fun text(): String = buildString {
        line(KEY_SESSION, session)
        line(KEY_VERSION, version)
        line(KEY_SIGNAL, signal)
        line(KEY_SECONDS, seconds.toString())
        exceptionType?.let { line(KEY_EXCEPTION, it) }
        for (frame in frames) line(KEY_FRAME, frame)
    }

    fun event(at: Instant): Event.Crash =
        Event.Crash(at, session, version, signal, frames, seconds, exceptionType)

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append('=').append(value).append('\n')
    }

    companion object {
        const val TERMINATE = "TERMINATE"
        const val MAX_CHARS = 64 * 1024
        const val KEY_SESSION = "session"
        const val KEY_VERSION = "version"
        const val KEY_SIGNAL = "signal"
        const val KEY_SECONDS = "seconds"
        const val KEY_EXCEPTION = "exception"
        const val KEY_FRAME = "frame"
        private const val MAX_LINE = 9_999_999

        fun of(thrown: Throwable, session: String, version: String, seconds: Long): CrashReport {
            val chain = causes(thrown)
            val framed = chain.asReversed().firstOrNull { it.stackTrace.isNotEmpty() }
            val frames =
                framed?.stackTrace.orEmpty()
                    .asSequence()
                    .mapNotNull { EventCodec.frame(frame(it)) }
                    .take(EventCodec.MAX_FRAMES)
                    .toList()
            return CrashReport(
                session = session,
                version = EventCodec.version(version),
                frames = frames,
                seconds = EventCodec.count(seconds),
                exceptionType = EventCodec.exceptionType(chain.last().javaClass.name),
            )
        }

        fun frame(element: StackTraceElement): String =
            "${element.className}.${element.methodName}:${element.lineNumber.coerceIn(0, MAX_LINE)}"

        fun read(text: String): CrashReport? {
            if (text.isEmpty() || text.length > MAX_CHARS) return null
            var session = ""
            var version = ""
            var signal = ""
            var seconds = 0L
            var exceptionType: String? = null
            val frames = ArrayList<String>()
            for (line in text.split('\n')) {
                val equals = line.indexOf('=')
                if (equals < 0) continue
                val value = line.substring(equals + 1)
                when (line.substring(0, equals)) {
                    KEY_SESSION -> session = value

                    KEY_VERSION -> version = value

                    KEY_SIGNAL -> signal = value

                    KEY_SECONDS -> seconds = value.toLongOrNull()?.let(EventCodec::count) ?: 0

                    KEY_EXCEPTION -> exceptionType = EventCodec.exceptionType(value)

                    KEY_FRAME ->
                        if (frames.size < EventCodec.MAX_FRAMES) EventCodec.frame(value)?.let(frames::add)
                }
            }
            val valid =
                EventCodec.SESSION.matches(session) &&
                    EventCodec.VERSION.matches(version) &&
                    EventCodec.SIGNAL.matches(signal)
            return if (valid) CrashReport(session, version, signal, frames, seconds, exceptionType) else null
        }

        private fun causes(thrown: Throwable): List<Throwable> {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val chain = ArrayList<Throwable>()
            var current: Throwable? = thrown
            while (current != null && seen.add(current)) {
                chain += current
                current = current.cause
            }
            return chain
        }
    }
}
