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
                    QueueWalk.remaining(headThenVibe, order, current, false),
                )
            }
        }
        assertEquals(0, QueueWalk.next(headThenVibe, pass, 2, false))
        assertEquals(3, QueueWalk.next(headThenVibe, pass, 1, false))
        assertEquals(4, QueueWalk.remaining(headThenVibe, pass, 0, false))
    }

    @Test
    fun `TR-04 after the last row in play order nothing comes, and only the current track remains`() {
        val order = listOf(2, 0, 1, 3, 4)
        assertNull(QueueWalk.next(headThenVibe, order, 4, false))
        assertEquals(1, QueueWalk.remaining(headThenVibe, order, 4, false))
        assertNull(QueueWalk.next(emptyList(), emptyList(), 0, false))
        assertEquals(1, QueueWalk.remaining(emptyList(), emptyList(), 0, false))
    }

    @Test
    fun `TR-05 CONT-10 held by repeat, the head goes on to its next row and wraps, and the vibe waits`() {
        val pass = listOf(1, 2, 0, 3, 4)
        assertEquals(2, QueueWalk.next(headThenVibe, pass, 1, true))
        assertEquals(0, QueueWalk.next(headThenVibe, pass, 2, true))
        assertEquals(1, QueueWalk.next(headThenVibe, pass, 0, true))
        assertEquals(Int.MAX_VALUE, QueueWalk.remaining(headThenVibe, pass, 0, true))
        val head = entries("h0", "h1")
        assertEquals(0, QueueWalk.next(head, listOf(0, 1), 1, true))
    }

    @Test
    fun `TR-05 repeat over a head of one track plays that track again, with or without a vibe behind it`() {
        assertEquals(0, QueueWalk.next(entries("h0"), listOf(0), 0, true))
        assertEquals(0, QueueWalk.next(entries("h0") + continuation("c1"), listOf(0, 1), 0, true))
    }

    @Test
    fun `HOST-05 HOST-32 a waiting item comes first wherever it is, an ended jam's leftover too`() {
        val entries = entries("h0", "h1", "h2") + waiting("i3", "x") + entries("h4") + waiting(null, "y")
        val order = entries.indices.toList()
        assertEquals(3, QueueWalk.next(entries, order, 0, false))
        assertEquals(3, QueueWalk.next(entries, order, 4, true))
        assertEquals(5, QueueWalk.next(entries, order, 3, false))
        assertEquals(1 + 2 + 3, QueueWalk.remaining(entries, order, 0, false))
        assertEquals(1 + 2 + 0, QueueWalk.remaining(entries, order, 4, false))
        val leftover = entries("h0", "h1") + waiting(null, "z") + entries("h3")
        assertEquals(2, QueueWalk.next(leftover, listOf(3, 1, 0, 2), 3, false))
    }

    @Test
    fun `HOST-10 spent rows, played items and other rows, are skipped, and alone they end the walk`() {
        val entries = entries("h0") + played("i1", "x") + other("o2") + entries("h3") + other("o4")
        val order = entries.indices.toList()
        assertEquals(3, QueueWalk.next(entries, order, 0, false))
        assertEquals(2, QueueWalk.remaining(entries, order, 0, false))
        assertNull(QueueWalk.next(entries, order, 3, false))
        assertEquals(1, QueueWalk.remaining(entries, order, 3, false))
        assertEquals(0, QueueWalk.next(entries, order, 3, true))
    }

    @Test
    fun `HOST-36 rows that are leaving are never the target, waiting items and the wrap included`() {
        val entries = entries("h0") + waiting("i1", "x") + waiting("i2", "y") + entries("h3", "h4")
        val order = entries.indices.toList()
        assertEquals(2, QueueWalk.next(entries, order, 0, false, leaving = setOf(0, 1)))
        assertEquals(3, QueueWalk.next(entries, order, 0, false, leaving = setOf(0, 1, 2)))
        assertNull(QueueWalk.next(entries, order, 3, false, leaving = setOf(1, 2, 3, 4)))
        assertEquals(0, QueueWalk.next(entries, order, 4, true, leaving = setOf(1, 2, 4)))
        assertNull(QueueWalk.next(entries("h0"), listOf(0), 0, true, leaving = setOf(0)))
    }

    @Test
    fun `HOST-38 a base not yet begun goes to its start after the waiting items, and counts from it`() {
        val rows = other("o") + waiting("i1", "x") + entries("h0", "h1", "h2", "h3")
        val order = rows.indices.toList()
        assertEquals(1, QueueWalk.next(rows, order, 0, false, start = 4))
        assertEquals(4, QueueWalk.next(rows, order, 1, false, start = 4))
        assertEquals(1, QueueWalk.next(rows, order, 4, false, start = 4))
        val begun = other("o") + played("i1", "x") + entries("h0", "h1", "h2", "h3")
        assertEquals(5, QueueWalk.next(begun, order, 4, false, start = 4))
        assertEquals(3, QueueWalk.remaining(rows, order, 1, false, start = 4))
        assertEquals(5, QueueWalk.remaining(rows, order, 1, false))
        assertEquals(5, QueueWalk.next(rows, order, 1, false, leaving = setOf(4), start = 4))
        assertEquals(Int.MAX_VALUE, QueueWalk.remaining(rows, order, 1, true, start = 4))
    }

    private fun entries(vararg ids: String) = ids.map { QueueEntry(track(it)) }

    private fun waiting(itemId: String?, trackId: String) =
        listOf(QueueEntry(track(trackId), EntryPart.ITEM, item = ItemMark(itemId, "g1", waiting = true)))

    private fun played(itemId: String, trackId: String) =
        listOf(QueueEntry(track(trackId), EntryPart.ITEM, item = ItemMark(itemId, "g1", waiting = false)))

    private fun other(trackId: String) = listOf(QueueEntry(track(trackId), EntryPart.OTHER))

    private fun continuation(vararg ids: String) = ids.map { QueueEntry(track(it), EntryPart.CONTINUATION) }
}
