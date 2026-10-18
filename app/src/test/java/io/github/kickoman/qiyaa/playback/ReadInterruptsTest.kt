package io.github.kickoman.qiyaa.playback

import java.io.InterruptedIOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadInterruptsTest {
    private val pending = ArrayList<() -> Unit>()
    private val logged = ArrayList<String>()
    private val interrupts =
        ReadInterrupts(
            schedule = { delayMs, check ->
                assertEquals(ReadInterrupts.CHECK_PERIOD_MS, delayMs)
                synchronized(pending) { pending += check }
            },
            log = { synchronized(logged) { logged += it } },
        )
    private val client = OkHttpClient()

    @Test
    fun `TR-09 a read blocked when its load is canceled is canceled at the next check`() {
        val watch = interrupts.watch()
        val call = newCall()
        val reader = Reader { watch.during(call) { pause() } }
        reader.awaitPause()

        runChecks()
        assertFalse(call.isCanceled())
        reader.thread.interrupt()
        runChecks()

        assertTrue(call.isCanceled())
        assertEquals(listOf("Canceled the HTTP call of a canceled load stuck in a read"), logged)
        runChecks()
        assertEquals(1, logged.size)
        reader.finish()
    }

    @Test
    fun `TR-09 an open is watched from the moment it makes its call`() {
        val watch = interrupts.watch()
        val call = newCall()
        val reader =
            Reader {
                watch.during(null) {
                    pause()
                    watch.bind(call)
                    pause()
                }
            }
        reader.awaitPause()

        reader.thread.interrupt()
        runChecks()
        assertFalse(call.isCanceled())
        reader.resume()
        reader.awaitPause()
        runChecks()

        assertTrue(call.isCanceled())
        reader.finish()
    }

    @Test
    fun `a read on a thread that is not interrupted is never canceled`() {
        val watch = interrupts.watch()
        val call = newCall()
        val reader = Reader { watch.during(call) { pause() } }
        reader.awaitPause()

        repeat(3) { runChecks() }

        assertFalse(call.isCanceled())
        assertTrue(logged.isEmpty())
        reader.finish()
    }

    @Test
    fun `a read on an interrupted thread fails at once without reading`() {
        val watch = interrupts.watch()
        var read = false

        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { watch.during(newCall()) { read = true } }
        } finally {
            Thread.interrupted()
        }

        assertFalse(read)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `the check runs only while a read is in progress`() {
        val watch = interrupts.watch()
        assertTrue(pending.isEmpty())

        watch.during(newCall()) {}
        watch.during(newCall()) {}
        assertEquals(1, pending.size)
        runChecks()
        assertTrue(pending.isEmpty())

        val reader = Reader { watch.during(newCall()) { pause() } }
        reader.awaitPause()
        assertEquals(1, pending.size)
        runChecks()
        assertEquals(1, pending.size)
        reader.finish()
        runChecks()
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `a call bound from a thread that is not reading is not watched`() {
        val watch = interrupts.watch()
        val reader = Reader { watch.during(null) { pause() } }
        reader.awaitPause()
        val call = newCall()

        watch.bind(call)
        reader.thread.interrupt()
        runChecks()

        assertFalse(call.isCanceled())
        reader.finish()
    }

    private fun newCall(): Call = client.newCall(Request.Builder().url("http://localhost/").build())

    private fun runChecks() {
        val due = synchronized(pending) { pending.toList().also { pending.clear() } }
        due.forEach { it() }
    }

    // Waits like a blocked socket read: without touching the thread's interrupt flag.
    private class Reader(body: Reader.() -> Unit) {
        private val arrived = Semaphore(0)
        private val go = Semaphore(0)
        val thread = thread { body() }

        fun pause() {
            arrived.release()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!go.tryAcquire()) {
                check(System.nanoTime() < deadline) { "The test never resumed the reader" }
                Thread.yield()
            }
        }

        fun awaitPause() = assertTrue(arrived.tryAcquire(5, TimeUnit.SECONDS))

        fun resume() = go.release()

        fun finish() {
            resume()
            thread.join(5_000)
            assertFalse(thread.isAlive)
        }
    }
}
