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
    fun `items inserted after an index of the head follow it in the head, which goes on behind them`() {
        val shuffle = HeadShuffle(listOf(2, 0, 1), 5)
        val inserted = shuffle.insertedAfter(1, 2)
        assertEquals(HeadShuffle(listOf(4, 0, 1, 2, 3), 7), inserted)
        assertEquals(listOf(4, 0, 1, 2, 3, 5, 6), inserted.order)
        assertEquals(listOf(2, 3, 4, 0, 1, 5, 6), shuffle.insertedAfter(3, 2).order)
    }

    @Test
    fun `items inserted after an index of the rest follow it in index order, as plain inserted ones`() {
        val shuffle = HeadShuffle(listOf(1, 0), 5)
        assertEquals(shuffle.inserted(3, 1), shuffle.insertedAfter(3, 1))
        assertEquals(listOf(1, 0, 2, 3, 4, 5), shuffle.insertedAfter(3, 1).order)
        assertEquals(shuffle.inserted(5, 2), shuffle.insertedAfter(5, 2))
        assertEquals(shuffle.inserted(0, 2), shuffle.insertedAfter(0, 2))
    }

    @Test
    fun `items inserted after nothing into an empty order become the head`() {
        assertEquals(HeadShuffle(listOf(0, 1, 2), 3), HeadShuffle.EMPTY.insertedAfter(0, 3))
    }

    @Test
    fun `a pass over any head is the lead, then the rest of the head shuffled, then the other indices`() {
        repeat(20) { seed ->
            val shuffle = HeadShuffle.pass(listOf(1, 3, 4, 5), length = 7, lead = listOf(3, 6), Random(seed))
            assertEquals(listOf(3, 6), shuffle.order.take(2))
            assertEquals(setOf(1, 4, 5), shuffle.order.subList(2, 5).toSet())
            assertEquals(listOf(0, 2), shuffle.order.drop(5))
        }
        val empty = HeadShuffle.pass(listOf(0, 1), length = 3, lead = emptyList(), Random(0))
        assertEquals(setOf(0, 1), empty.head.toSet())
        assertEquals(2, empty.order.last())
    }

    @Test
    fun `the pass of a head size and a first track is the pass of the head prefix led by that track`() {
        for (first in listOf(-1, 2, 5)) {
            assertEquals(
                HeadShuffle.pass(listOf(0, 1, 2, 3), 6, listOf(first).filter { it < 4 }, Random(3)),
                HeadShuffle.pass(headSize = 4, length = 6, first = first, random = Random(3)),
            )
        }
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

    @Test
    fun `ADD-03 joined tracks join the pass after the current track and the rows pinned behind it`() {
        val pass = HeadShuffle(listOf(2, 0, 3, 1), 6)
        repeat(20) { seed ->
            val joined = pass.joined(at = 4, count = 2, current = 0, after = 1, random = Random(seed))
            assertEquals(8, joined.length)
            assertEquals(listOf(2, 0, 3), joined.head.take(3))
            assertEquals(setOf(0, 1, 2, 3, 4, 5), joined.head.toSet())
            assertEquals(listOf(6, 7), joined.order.drop(6))
        }
        val spread = (0 until 50).map { pass.joined(4, 1, 0, 0, Random(it)).head.indexOf(4) }.toSet()
        assertEquals(setOf(2, 3, 4), spread)
    }

    @Test
    fun `ADD-03 joined tracks shift the indices at and after them, outside the pass they go to its end`() {
        val pass = HeadShuffle(listOf(1, 0), 3)
        val joined = pass.joined(at = 1, count = 1, current = 2, after = 0, random = Random(0))
        assertEquals(listOf(2, 0, 1), joined.head)
        assertEquals(listOf(2, 0, 1, 3), joined.order)
    }

    @Test
    fun `ADD-07 tracks inserted before a row of the pass stand right in front of it`() {
        val pass = HeadShuffle(listOf(0, 3, 1), 5)
        assertEquals(listOf(0, 3, 4, 5, 1), pass.insertedBefore(3, 2).head)
        assertEquals(listOf(0, 3, 4, 5, 1, 2, 6), pass.insertedBefore(3, 2).order)
        assertEquals(pass.inserted(2, 1), pass.insertedBefore(2, 1))
    }
}
