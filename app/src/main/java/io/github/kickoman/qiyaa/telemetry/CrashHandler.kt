package io.github.kickoman.qiyaa.telemetry

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class CrashHandler(
    private val file: File,
    private val session: String,
    private val version: String,
    private val uptimeMs: () -> Long,
) : Thread.UncaughtExceptionHandler {
    private val crashed = AtomicBoolean(false)

    @Volatile
    private var active = false

    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !== this) previous = current
        active = true
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    fun uninstall() {
        active = false
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === this) Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    override fun uncaughtException(thread: Thread, thrown: Throwable) {
        try {
            if (active && crashed.compareAndSet(false, true)) write(thrown)
        } catch (ignored: Throwable) {
            // The report is best effort: the previous handler below must run whatever happened here.
        }
        val next = previous
        if (next != null) next.uncaughtException(thread, thrown) else thrown.printStackTrace()
    }

    private fun write(thrown: Throwable) {
        val report = CrashReport.of(thrown, session, version, uptimeMs() / MS_PER_SECOND)
        val directory = file.absoluteFile.parentFile
        directory?.mkdirs()
        val temporary = File(directory, file.name + TEMPORARY_SUFFIX)
        temporary.writeText(report.text())
        temporary.renameTo(file)
    }

    companion object {
        const val TEMPORARY_SUFFIX = ".tmp"
        private const val MS_PER_SECOND = 1_000L
    }
}
