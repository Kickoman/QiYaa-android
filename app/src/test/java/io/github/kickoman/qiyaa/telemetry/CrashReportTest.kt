package io.github.kickoman.qiyaa.telemetry

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null
    private val previous = RecordingHandler()

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test
    fun `TEL-05 an uncaught exception on a thread leaves a report the next run reads back`() {
        val file = File(folder.root, "telemetry/$SESSION.crash")
        val handler = CrashHandler(file, SESSION, "0.6.0", uptimeMs = { 5_423_999 })
        handler.install()

        crash { throw RuntimeException("wrapped", IllegalStateException(MESSAGE)) }

        assertEquals(1, previous.calls)
        val text = file.readText()
        assertFalse(text, text.contains(MESSAGE))
        assertFalse(text, text.contains("Кино"))
        val report = CrashReport.read(text)!!
        assertEquals(SESSION, report.session)
        assertEquals("0.6.0", report.version)
        assertEquals(CrashReport.TERMINATE, report.signal)
        assertEquals(5_423L, report.seconds)
        assertEquals("java.lang.IllegalStateException", report.exceptionType)
        assertTrue(report.frames.size in 1..EventCodec.MAX_FRAMES)
        assertTrue(report.frames.toString(), report.frames.all(EventCodec.FRAME::matches))
        val testClass = CrashReportTest::class.java.name
        assertTrue(report.frames.toString(), report.frames.first().startsWith(testClass))
        assertEquals(listOf("$SESSION.crash"), file.parentFile!!.list()!!.toList())

        handler.uninstall()
        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `TEL-05 a stack overflow gives 32 frames`() {
        val file = File(folder.root, "$SESSION.crash")
        CrashHandler(file, SESSION, "0.6.0", uptimeMs = { 0 }).install()

        crash { recurse(0) }

        val report = CrashReport.read(file.readText())!!
        assertEquals("java.lang.StackOverflowError", report.exceptionType)
        assertEquals(EventCodec.MAX_FRAMES, report.frames.size)
        assertTrue(report.frames.all { it.startsWith("${CrashReportTest::class.java.name}.recurse:") })
    }

    @Test
    fun `the first crash wins and every crash reaches the previous handler`() {
        val file = File(folder.root, "$SESSION.crash")
        CrashHandler(file, SESSION, "0.6.0", uptimeMs = { 0 }).install()

        crash { throw IllegalStateException("first") }
        crash { throw IllegalArgumentException("second") }

        assertEquals(2, previous.calls)
        assertEquals("java.lang.IllegalStateException", CrashReport.read(file.readText())!!.exceptionType)
    }

    @Test
    fun `installing twice still reaches the previous handler once`() {
        val file = File(folder.root, "$SESSION.crash")
        val handler = CrashHandler(file, SESSION, "0.6.0", uptimeMs = { 0 })
        handler.install()
        handler.install()

        crash { throw IllegalStateException("once") }

        assertEquals(1, previous.calls)
        handler.uninstall()
        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `the previous handler runs even when the report cannot be written`() {
        val blocker = folder.newFile("not-a-directory")
        CrashHandler(File(blocker, "$SESSION.crash"), SESSION, "0.6.0", uptimeMs = { 0 }).install()

        crash { throw IllegalStateException("no place for the report") }

        assertEquals(1, previous.calls)
    }

    @Test
    fun `uninstall restores the previous handler only while ours is the default, and then only forwards`() {
        val file = File(folder.root, "$SESSION.crash")
        val handler = CrashHandler(file, SESSION, "0.6.0", uptimeMs = { 0 })
        handler.install()
        val wrapper = Thread.UncaughtExceptionHandler(handler::uncaughtException)
        Thread.setDefaultUncaughtExceptionHandler(wrapper)

        handler.uninstall()
        assertSame(wrapper, Thread.getDefaultUncaughtExceptionHandler())

        crash { throw IllegalStateException("after uninstall") }
        assertEquals(1, previous.calls)
        assertFalse(file.exists())
    }

    @Test
    fun `a root cause without frames takes the outer exception's`() {
        val outer = RuntimeException("outer", Frameless())
        val report = CrashReport.of(outer, SESSION, "0.6.0", 7)
        assertEquals(Frameless::class.java.name, report.exceptionType)
        assertEquals(outer.stackTrace.size.coerceAtMost(EventCodec.MAX_FRAMES), report.frames.size)
        assertEquals(EventCodec.frame(CrashReport.frame(outer.stackTrace[0])), report.frames[0])
    }

    @Test
    fun `a cycle in the cause chain ends the walk`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second", first)
        first.initCause(second)
        val report = CrashReport.of(first, SESSION, "0.6.0", 0)
        assertEquals("java.lang.IllegalArgumentException", report.exceptionType)
    }

    @Test
    fun `a frame is class, method and line, and an unknown or native line is 0`() {
        assertEquals("a.B.m:12", CrashReport.frame(StackTraceElement("a.B", "m", "B.kt", 12)))
        assertEquals("a.B.m:0", CrashReport.frame(StackTraceElement("a.B", "m", "B.kt", -1)))
        assertEquals("a.B.run:0", CrashReport.frame(StackTraceElement("a.B", "run", null, -2)))
        assertEquals("a.B\$1.<init>:3", CrashReport.frame(StackTraceElement("a.B\$1", "<init>", "B.kt", 3)))
    }

    @Test
    fun `a frame over 200 characters is cut to 200 and keeps its line`() {
        assertEquals("x".repeat(200) + ":12", EventCodec.frame("x".repeat(300) + ":12"))
        assertEquals("x".repeat(200) + ":0", EventCodec.frame("x".repeat(201)))
        val thrown =
            IllegalStateException().apply {
                stackTrace = arrayOf(StackTraceElement("a".repeat(400), "run", null, 7))
            }
        assertEquals(listOf("a".repeat(200) + ":7"), CrashReport.of(thrown, SESSION, "0.6.0", 1).frames)
    }

    @Test
    fun `the text round-trips and keeps the order of frames`() {
        val report =
            CrashReport(SESSION, "0.6.0", "TERMINATE", listOf("a.B.m:1", "c.D.n:0"), 42, "java.lang.Error")
        assertEquals(report, CrashReport.read(report.text()))
        assertEquals(
            "session=$SESSION\nversion=0.6.0\nsignal=TERMINATE\nseconds=42\n" +
                "exception=java.lang.Error\nframe=a.B.m:1\nframe=c.D.n:0\n",
            report.text(),
        )
    }

    @Test
    fun `reading leaves out an exception type or a frame that does not fit and keeps the rest`() {
        val text =
            "session=$SESSION\nversion=0.6.0\nsignal=TERMINATE\nseconds=-5\n" +
                "exception=java.io.FileNotFoundException: /sdcard/Кино.mp3\n" +
                "frame=a b.c:12\nframe=:3\nframe=a.B.m:1\nunknown=x\n"
        assertEquals(
            CrashReport(SESSION, "0.6.0", "TERMINATE", listOf("a_b.c:12", "a.B.m:1"), 0, null),
            CrashReport.read(text),
        )
    }

    @Test
    fun `empty, foreign or oversized text gives no report`() {
        val valid = CrashReport(SESSION, "0.6.0").text()
        assertNull(CrashReport.read(""))
        assertNull(CrashReport.read("hello\nworld\n"))
        assertNull(CrashReport.read(valid.replace(SESSION, "3f2b9c1d7a6e5048")))
        assertNull(CrashReport.read(valid.replace("version=0.6.0", "version=0.6 beta")))
        assertNull(CrashReport.read(valid.replace("signal=TERMINATE", "signal=terminate")))
        assertNull(CrashReport.read(valid + "frame=a.B.m:1\n".repeat(5_000)))
    }

    private fun crash(body: () -> Unit) {
        val thread = Thread(body, "crasher")
        thread.start()
        thread.join()
    }

    private fun recurse(depth: Int): Int = recurse(depth + 1) + 1

    private class Frameless : RuntimeException("no frames", null, false, false)

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        @Volatile
        var calls = 0

        override fun uncaughtException(thread: Thread, thrown: Throwable) {
            calls++
        }
    }

    private companion object {
        const val SESSION = "5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53"
        const val MESSAGE = "/storage/emulated/0/Music/Кино - Группа крови.mp3"
    }
}
