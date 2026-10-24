package io.github.kickoman.qiyaa.local

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    private val order = NaturalOrder(Locale.ROOT)

    @Test
    fun `LOCAL-11 numbers compare by value, digits before text, text without case`() {
        val names = listOf("10 b", "2 a", "b", "A", "02 a", "Track 9", "track 10", "1")
        assertEquals(
            listOf("1", "02 a", "2 a", "10 b", "A", "b", "Track 9", "track 10"),
            names.sortedWith(order),
        )
    }

    @Test
    fun `LOCAL-11 names equal so far compare as plain strings, so the order is total`() {
        assertTrue(order.compare("02", "2") != 0)
        assertTrue(order.compare("a", "A") != 0)
        assertEquals(0, order.compare("same", "same"))
    }

    @Test
    fun `LOCAL-11 paths compare by component, a folder's name among its siblings' names`() {
        val paths =
            listOf(
                listOf("A", "c.mp3"),
                listOf("A", "b", "x.mp3"),
                listOf("A", "a.mp3"),
                listOf("A b", "z.mp3"),
            )
        assertEquals(
            listOf(
                listOf("A", "a.mp3"),
                listOf("A", "b", "x.mp3"),
                listOf("A", "c.mp3"),
                listOf("A b", "z.mp3"),
            ),
            paths.sortedWith(order::comparePaths),
        )
    }

    @Test
    fun `LOCAL-11 case is folded the same in every locale, as on the desktop`() {
        val turkish = NaturalOrder(Locale.forLanguageTag("tr"))
        assertEquals(listOf("ia", "Iz"), listOf("Iz", "ia").sortedWith(turkish))
    }
}
