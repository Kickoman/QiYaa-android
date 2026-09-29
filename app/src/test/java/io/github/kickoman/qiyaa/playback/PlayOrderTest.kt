package io.github.kickoman.qiyaa.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayOrderTest {
    @Test
    fun `in sequential order the remainder counts the tracks after the current one`() {
        val order = listOf(0, 1, 2, 3, 4)
        assertEquals(4, PlayOrder.remainingAfter(order, 0))
        assertEquals(1, PlayOrder.remainingAfter(order, 3))
    }

    @Test
    fun `in shuffled order the remainder follows the play order, not the timeline index`() {
        val order = listOf(3, 0, 4, 1, 2)
        assertEquals(2, PlayOrder.remainingAfter(order, 4))
        assertEquals(0, PlayOrder.remainingAfter(order, 2))
        assertEquals(4, PlayOrder.remainingAfter(order, 3))
    }

    @Test
    fun `the last track in play order has nothing after it`() {
        assertEquals(0, PlayOrder.remainingAfter(listOf(0, 1, 2), 2))
    }

    @Test
    fun `a current index outside the order counts the whole order`() {
        assertEquals(3, PlayOrder.remainingAfter(listOf(0, 1, 2), 7))
        assertEquals(0, PlayOrder.remainingAfter(emptyList(), 0))
    }
}
