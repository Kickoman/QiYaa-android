package io.github.kickoman.qiyaa.ui

import org.junit.Assert.assertEquals
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
}
