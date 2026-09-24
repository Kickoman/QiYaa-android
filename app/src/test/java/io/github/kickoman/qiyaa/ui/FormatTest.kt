package io.github.kickoman.qiyaa.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun times() {
        assertEquals("00:00", fmtTime(0))
        assertEquals("04:12", fmtTime(252000))
        assertEquals("1:01:05", fmtTime(3665000))
    }

    @Test
    fun balance() {
        assertEquals("C", balanceLabel(0))
        assertEquals("L25", balanceLabel(-25))
        assertEquals("R100", balanceLabel(100))
    }

    @Test
    fun readoutAndDb() {
        assertEquals("320K · 44 · ST", formatReadout(320, 44100, 2))
        assertEquals("--K · -- · --", formatReadout(0, 0, 0))
        assertEquals("192K · 48 · MO", formatReadout(192, 48000, 1))
        assertEquals("0", formatDb(0.0))
        assertEquals("+3.5", formatDb(3.5))
        assertEquals("-12", formatDb(-12.0))
    }
}
