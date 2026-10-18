package io.github.kickoman.qiyaa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleChoiceTest {
    @Test
    fun `SHUF-10 the stored values read back and anything else is without repeats`() {
        assertEquals(listOf("without-repeats", "random"), ShuffleChoice.entries.map { it.key })
        assertEquals(ShuffleChoice.RANDOM_TRACK, ShuffleChoice.fromKey("random"))
        assertEquals(ShuffleChoice.WITHOUT_REPEATS, ShuffleChoice.fromKey("without-repeats"))
        assertEquals(ShuffleChoice.WITHOUT_REPEATS, ShuffleChoice.fromKey("shuffled"))
        assertEquals(ShuffleChoice.WITHOUT_REPEATS, ShuffleChoice.fromKey(null))
    }
}
