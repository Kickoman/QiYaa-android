package io.github.kickoman.qiyaa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test
    fun `formatTime renders mm colon ss and switches to h colon mm colon ss past an hour`() {
        assertEquals("00:00", formatTime(0))
        assertEquals("04:12", formatTime(252000))
        assertEquals("1:01:05", formatTime(3665000))
        assertEquals("00:00", formatTime(-5000))
    }

    @Test
    fun `timeLabel shows the playing position, elapsed or remaining, when there is no drag`() {
        assertEquals("01:00", label(positionMs = 60_000, remaining = false))
        assertEquals("-03:00", label(positionMs = 60_000, remaining = true))
    }

    @Test
    fun `timeLabel shows the drag position while dragging, elapsed or remaining, not the playing one`() {
        assertEquals("03:00", label(positionMs = 60_000, remaining = false, drag = 0.75f))
        assertEquals("-01:00", label(positionMs = 60_000, remaining = true, drag = 0.75f))
    }

    @Test
    fun `timeLabel shows the start and the end for a drag at either end of the bar`() {
        assertEquals("00:00", label(positionMs = 60_000, remaining = false, drag = 0f))
        assertEquals("04:00", label(positionMs = 60_000, remaining = false, drag = 1f))
        assertEquals("-04:00", label(positionMs = 60_000, remaining = true, drag = 0f))
        assertEquals("-00:00", label(positionMs = 60_000, remaining = true, drag = 1f))
    }

    @Test
    fun `timeLabel ignores the drag while the duration is unknown, as the seek does`() {
        assertNull(positionAt(0.5f, 0))
        assertEquals("00:42", label(positionMs = 42_000, durationMs = 0, remaining = false, drag = 0.5f))
    }

    @Test
    fun `timeLabel is 00 colon 00 without a track, whatever the mode, position or drag`() {
        assertEquals("00:00", label(positionMs = 60_000, remaining = false, hasTrack = false))
        assertEquals("00:00", label(positionMs = 60_000, remaining = true, hasTrack = false))
        assertEquals("00:00", label(positionMs = 0, durationMs = 0, remaining = true, hasTrack = false))
        assertEquals("00:00", label(positionMs = 0, remaining = false, hasTrack = false, drag = 0.5f))
    }

    @Test
    fun `TR-08 the release shows the position the drag showed, at the duration known at release`() {
        val fraction = 0.37f
        assertEquals("01:29", label(positionMs = 0, remaining = false, durationMs = 240_000, drag = fraction))
        val dragging = label(positionMs = 0, remaining = false, durationMs = 300_000, drag = fraction)
        val target = positionAt(fraction, 300_000)!!
        val released = label(positionMs = target, remaining = false, durationMs = 300_000)
        assertEquals("01:51", dragging)
        assertEquals(dragging, released)
    }

    @Test
    fun `positionAt clamps the fraction to the bar`() {
        assertEquals(0L, positionAt(-0.5f, 240_000))
        assertEquals(240_000L, positionAt(1.5f, 240_000))
        assertEquals(60_000L, positionAt(0.25f, 240_000))
    }

    @Test
    fun `balanceLabel is C at the centre and L or R with the magnitude`() {
        assertEquals("C", balanceLabel(0))
        assertEquals("L25", balanceLabel(-25))
        assertEquals("R100", balanceLabel(100))
    }

    @Test
    fun `formatReadout shows kbps, kHz and channel mode with dashes while unknown`() {
        assertEquals("320K · 44 · ST", formatReadout(320, 44100, 2))
        assertEquals("--K · -- · --", formatReadout(0, 0, 0))
        assertEquals("192K · 48 · MO", formatReadout(192, 48000, 1))
    }

    @Test
    fun `formatDb drops the fraction when it is zero and signs positive values`() {
        assertEquals("0", formatDb(0.0))
        assertEquals("+3.5", formatDb(3.5))
        assertEquals("-12", formatDb(-12.0))
    }

    private fun label(
        positionMs: Long,
        remaining: Boolean,
        durationMs: Long = 240_000,
        hasTrack: Boolean = true,
        drag: Float? = null,
    ): String = timeLabel(positionMs, durationMs, hasTrack, remaining, drag)
}
