package io.github.kickoman.qiyaa.jam

import io.github.kickoman.qiyaa.support.ECMASCRIPT_SPACES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JamNamesTest {
    @Test
    fun `GUEST-02 a name is trimmed and its JavaScript spaces collapsed, 1 to 24 code points`() {
        for (space in ECMASCRIPT_SPACES) {
            val name = "${space}А$space${space}ня$space"
            assertEquals("U+%04X".format(space.code), "А ня", JamNames.clean(name))
        }
        assertEquals("Аня", JamNames.clean("  Аня  "))
        assertEquals(GUITAR.repeat(24), JamNames.clean(GUITAR.repeat(24)))
        assertNull(JamNames.clean(GUITAR.repeat(25)))
        assertEquals("x".repeat(24), JamNames.clean(" " + "x".repeat(24) + "\uFEFF"))
        assertNull(JamNames.clean("x".repeat(25)))
        assertNull(JamNames.clean(""))
        assertNull(JamNames.clean(" \u00A0\u3000\uFEFF "))
        for (cleaned in ECMASCRIPT_SPACES.map { JamNames.clean("${it}Аня$it")!! }) {
            assertTrue(cleaned, JamCodec.decodeClient(JamCodec.encode(joinOf(cleaned))) is Decoded.Message)
        }
    }

    @Test
    fun `GUEST-02 the prefilled name is cleaned and cut to 24 code points`() {
        assertEquals("Аня Ковалёва", JamNames.prefill(" Аня　 Ковалёва"))
        assertEquals(GUITAR.repeat(24), JamNames.prefill(GUITAR.repeat(30)))
        assertEquals("x".repeat(23), JamNames.prefill("x".repeat(23) + " " + "y".repeat(10)))
        assertEquals("", JamNames.prefill("   "))
        for (name in listOf("Аня Ковалёва", GUITAR.repeat(30), "x".repeat(40))) {
            assertEquals(name, JamNames.prefill(name), JamNames.clean(JamNames.prefill(name)))
        }
    }

    @Test
    fun `the name field is cut to 24 code points, never inside a surrogate pair, and is not cleaned`() {
        assertEquals(GUITAR.repeat(24), JamNames.cut(GUITAR.repeat(30)))
        assertEquals("x".repeat(23) + GUITAR, JamNames.cut("x".repeat(23) + GUITAR + "y"))
        assertEquals("Маша Петрова ", JamNames.cut("Маша Петрова "))
        assertEquals(" Маша\uFEFF ", JamNames.cut(" Маша\uFEFF "))
    }

    private fun joinOf(name: String) = Join("g1", "7k3m9q2x", "WDkyFgMr5iV3hKwManPvsg", PARTICIPANT, name)

    private companion object {
        const val GUITAR = "\uD83C\uDFB8"
        const val PARTICIPANT = "3f6c1a2e-8b4d-4c7a-9e21-5d0b7f3a1c64"
    }
}
