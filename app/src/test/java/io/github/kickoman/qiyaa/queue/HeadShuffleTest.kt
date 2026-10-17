package io.github.kickoman.qiyaa.queue

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HeadShuffleTest {
    @Test
    fun `a pass starts with the first track, has each head index once, and the rest follow in index order`() {
        repeat(20) { seed ->
            val shuffle = HeadShuffle.pass(headSize = 6, length = 9, first = 4, random = Random(seed))
            assertEquals(4, shuffle.order.first())
            assertEquals(6, shuffle.head.size)
            assertEquals((0 until 6).toSet(), shuffle.head.toSet())
            assertEquals(listOf(6, 7, 8), shuffle.order.drop(6))
        }
    }

    @Test
    fun `a pass with the first track outside the head shuffles the head and starts anywhere in it`() {
        for (first in listOf(-1, 3, 4)) {
            val shuffle = HeadShuffle.pass(headSize = 3, length = 5, first = first, random = Random(1))
            assertEquals(setOf(0, 1, 2), shuffle.head.toSet())
            assertEquals(listOf(3, 4), shuffle.order.drop(3))
        }
    }

    @Test
    fun `a pass of a head longer than the queue covers the queue`() {
        val shuffle = HeadShuffle.pass(headSize = 7, length = 3, first = 1, random = Random(2))
        assertEquals(3, shuffle.head.size)
        assertEquals(1, shuffle.order.first())
    }

    @Test
    fun `the order is the head in its order, then every other index ascending`() {
        assertEquals(listOf(3, 0, 1, 2, 4), HeadShuffle(listOf(3, 0), 5).order)
        assertEquals(emptyList<Int>(), HeadShuffle.EMPTY.order)
    }

    @Test
    fun `inserted items never join the head, and the head keeps its order`() {
        val shuffle = HeadShuffle(listOf(2, 0, 1), 4)
        val inserted = shuffle.inserted(1, 2)
        assertEquals(HeadShuffle(listOf(4, 0, 3), 6), inserted)
        assertEquals(listOf(4, 0, 3, 1, 2, 5), inserted.order)
        assertEquals(listOf(2, 0, 1, 3, 4, 5), shuffle.inserted(4, 2).order)
    }

    @Test
    fun `items inserted into an empty order become the head`() {
        assertEquals(HeadShuffle(listOf(0, 1, 2), 3), HeadShuffle.EMPTY.inserted(0, 3))
    }

    @Test
    fun `removing drops the removed indices from the head and shifts the ones after them`() {
        val shuffle = HeadShuffle(listOf(3, 0, 4, 1), 6)
        assertEquals(HeadShuffle(listOf(1, 0, 2), 4), shuffle.removed(1, 3))
        assertEquals(listOf(1, 0, 2, 3), shuffle.removed(1, 3).order)
        assertEquals(HeadShuffle.EMPTY, shuffle.removed(0, 6))
    }

    @Test
    fun `a head that is not a set of indices below the length is refused`() {
        assertThrows(IllegalArgumentException::class.java) { HeadShuffle(listOf(0, 0), 2) }
        assertThrows(IllegalArgumentException::class.java) { HeadShuffle(listOf(2), 2) }
        assertThrows(IllegalArgumentException::class.java) { HeadShuffle(listOf(0), 2).removed(1, 3) }
        assertThrows(IllegalArgumentException::class.java) { HeadShuffle(listOf(0), 2).inserted(3, 1) }
    }
}
