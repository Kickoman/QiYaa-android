package io.github.kickoman.qiyaa.queue

import org.junit.Assert.assertEquals
import org.junit.Test

class StartHistoryTest {
    @Test
    fun `CONT-05 the latest start comes first, each track once, at most five`() {
        val history = StartHistory()
        for (id in listOf("a", "b", "c", "b", "d", "e", "f", "g")) history.note(id)
        assertEquals(listOf("g", "f", "e", "d", "b"), history.ids)
    }

    @Test
    fun `CONT-05 seeds are track ids without an album`() {
        val history = StartHistory()
        history.note("10")
        history.note("20")
        assertEquals(listOf("track:20", "track:10"), history.seeds())
    }

    @Test
    fun `a stored history is read the same way, and clear forgets it`() {
        val history = StartHistory(listOf("a", "", "b", "a", "c", "d", "e", "f"))
        assertEquals(listOf("a", "b", "c", "d", "e"), history.ids)
        history.clear()
        assertEquals(emptyList<String>(), history.seeds())
    }
}
