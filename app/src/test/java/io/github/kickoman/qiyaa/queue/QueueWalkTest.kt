package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueWalkTest {
    private val headThenVibe = entries("h0", "h1", "h2") + continuation("c3", "c4")

    @Test
    fun `TR-03 CONT-09 without repeat the walk is the engine's order, the head or its pass, then the vibe`() {
        val identity = headThenVibe.indices.toList()
        val pass = listOf(2, 0, 1, 3, 4)
        for (order in listOf(identity, pass)) {
            for ((position, current) in order.withIndex()) {
                val next = QueueWalk.next(headThenVibe, order, current, false)
                assertEquals(order.getOrNull(position + 1), next)
                assertEquals(
                    PlayOrder.remainingAfter(order, current) + 1,
                    QueueWalk.remaining(order, current, false),
                )
            }
        }
        assertEquals(0, QueueWalk.next(headThenVibe, pass, 2, false))
        assertEquals(3, QueueWalk.next(headThenVibe, pass, 1, false))
        assertEquals(4, QueueWalk.remaining(pass, 0, false))
    }

    @Test
    fun `TR-04 after the last row in play order nothing comes, and only the current track remains`() {
        val order = listOf(2, 0, 1, 3, 4)
        assertNull(QueueWalk.next(headThenVibe, order, 4, false))
        assertEquals(1, QueueWalk.remaining(order, 4, false))
        assertNull(QueueWalk.next(emptyList(), emptyList(), 0, false))
        assertEquals(1, QueueWalk.remaining(emptyList(), 0, false))
    }

    @Test
    fun `TR-05 CONT-10 held by repeat, the head goes on to its next row and wraps, and the vibe waits`() {
        val pass = listOf(1, 2, 0, 3, 4)
        assertEquals(2, QueueWalk.next(headThenVibe, pass, 1, true))
        assertEquals(0, QueueWalk.next(headThenVibe, pass, 2, true))
        assertEquals(1, QueueWalk.next(headThenVibe, pass, 0, true))
        assertEquals(Int.MAX_VALUE, QueueWalk.remaining(pass, 0, true))
        val head = entries("h0", "h1")
        assertEquals(0, QueueWalk.next(head, listOf(0, 1), 1, true))
    }

    @Test
    fun `TR-05 repeat over a head of one track plays that track again, with or without a vibe behind it`() {
        assertEquals(0, QueueWalk.next(entries("h0"), listOf(0), 0, true))
        assertEquals(0, QueueWalk.next(entries("h0") + continuation("c1"), listOf(0, 1), 0, true))
    }

    private fun entries(vararg ids: String) = ids.map { QueueEntry(track(it)) }

    private fun continuation(vararg ids: String) = ids.map { QueueEntry(track(it), EntryPart.CONTINUATION) }
}
