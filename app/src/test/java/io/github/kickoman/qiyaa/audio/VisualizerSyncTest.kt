package io.github.kickoman.qiyaa.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A sample rate of 1000 Hz makes one frame one millisecond, and each written sample equals its frame number.
class VisualizerSyncTest {
    private val tap = VisualizerTap(1024).also { it.sampleRate = 1_000 }
    private val left = FloatArray(4)
    private val right = FloatArray(4)

    @Test
    fun `the window ends at the frame that sounds now, not at the newest one`() {
        tap.announceInput(0)
        tap.write(frames(0, 500), 500, 1)
        tap.reportPlaying(200_000, atNanos = 0)
        tap.readPlaying(left, right, 4, nowNanos = 0)
        assertArrayEquals(floatArrayOf(196f, 197f, 198f, 199f), left, 0f)
        assertArrayEquals(left, right, 0f)
    }

    @Test
    fun `chunks of one input buffer continue its timestamp`() {
        tap.announceInput(0)
        tap.write(frames(0, 100), 100, 1)
        tap.write(frames(100, 100), 100, 1)
        tap.reportPlaying(150_000, atNanos = 0)
        tap.readPlaying(left, right, 4, nowNanos = 0)
        assertArrayEquals(floatArrayOf(146f, 147f, 148f, 149f), left, 0f)
    }

    @Test
    fun `each input buffer is found by its own timestamp`() {
        tap.announceInput(0)
        tap.write(frames(0, 100), 100, 1)
        tap.announceInput(5_000_000)
        tap.write(frames(1_000, 100), 100, 1)
        tap.reportPlaying(5_020_000, atNanos = 0)
        tap.readPlaying(left, right, 4, nowNanos = 0)
        assertArrayEquals(floatArrayOf(1_016f, 1_017f, 1_018f, 1_019f), left, 0f)
    }

    @Test
    fun `the playing position runs on from the last report, but not past the newest audio`() {
        tap.announceInput(0)
        tap.write(frames(0, 500), 500, 1)
        tap.reportPlaying(100_000, atNanos = 0)
        tap.readPlaying(left, right, 4, nowNanos = 50_000_000)
        assertArrayEquals(floatArrayOf(146f, 147f, 148f, 149f), left, 0f)
        tap.readPlaying(left, right, 4, nowNanos = 10_000_000_000)
        assertArrayEquals(floatArrayOf(496f, 497f, 498f, 499f), left, 0f)
    }

    @Test
    fun `without timestamps the newest frames are shown, as before`() {
        tap.write(frames(0, 500), 500, 1)
        tap.readPlaying(left, right, 4, nowNanos = 0)
        assertArrayEquals(floatArrayOf(496f, 497f, 498f, 499f), left, 0f)
    }

    @Test
    fun `clear forgets the timestamps and the playing position`() {
        tap.announceInput(0)
        tap.write(frames(0, 500), 500, 1)
        tap.reportPlaying(200_000, atNanos = 0)
        tap.clear()
        assertNull(tap.lagUs(nowNanos = 0))
        tap.write(frames(1_000, 10), 10, 1)
        tap.readPlaying(left, right, 4, nowNanos = 0)
        assertArrayEquals(floatArrayOf(1_006f, 1_007f, 1_008f, 1_009f), left, 0f)
    }

    @Test
    fun `the lag is the time from the sounding frame to the newest written one`() {
        tap.announceInput(0)
        tap.write(frames(0, 500), 500, 1)
        tap.reportPlaying(200_000, atNanos = 0)
        assertEquals(300_000L, tap.lagUs(nowNanos = 0))
    }

    private fun frames(first: Int, count: Int) = FloatArray(count) { (first + it).toFloat() }
}
