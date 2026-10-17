package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import io.github.kickoman.qiyaa.queue.HeadShuffle
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadShuffleOrderTest {
    private val pass = HeadShuffle(listOf(3, 1, 0, 2), 6)

    @Test
    fun `the order walks the head, then the rest, with no index past either end`() {
        val order = HeadShuffleOrder(HeadShuffle(listOf(2, 0), 4), Random(0))
        assertEquals(4, order.length)
        assertEquals(listOf(2, 0, 1, 3), walk(order))
        assertEquals(listOf(3, 1, 0, 2), walkBack(order))
        assertEquals(C.INDEX_UNSET, order.getNextIndex(3))
        assertEquals(C.INDEX_UNSET, order.getPreviousIndex(2))
    }

    @Test
    fun `an empty order has no first and no last index`() {
        val order = HeadShuffleOrder(HeadShuffle.EMPTY, Random(0))
        assertEquals(0, order.length)
        assertEquals(C.INDEX_UNSET, order.firstIndex)
        assertEquals(C.INDEX_UNSET, order.lastIndex)
    }

    @Test
    fun `inserting and removing give the order HeadShuffle gives`() {
        val shuffle = HeadShuffle(listOf(2, 0, 1), 4)
        val order = HeadShuffleOrder(shuffle, Random(0))
        assertEquals(shuffle.inserted(1, 2), (order.cloneAndInsert(1, 2) as HeadShuffleOrder).shuffle)
        assertEquals(shuffle.removed(0, 2), (order.cloneAndRemove(0, 2) as HeadShuffleOrder).shuffle)
        assertEquals(0, order.cloneAndClear().length)
    }

    @Test
    fun `items inserted into an empty order without a staged pass become a pass of their own`() {
        val order = HeadShuffleOrder(HeadShuffle.EMPTY, Random(7)).cloneAndInsert(0, 5) as HeadShuffleOrder
        assertEquals(5, order.shuffle.head.size)
        assertEquals((0 until 5).toSet(), walk(order).toSet())
    }

    @Test
    fun `setMediaItems over a playlist gets exactly the staged pass, as ExoPlayer clones the order`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle(listOf(1, 0), 3), 3, pass, Random(0))
        assertEquals(3, installed.length)
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndRemove(0, 3).cloneAndInsert(0, 6)))
    }

    @Test
    fun `setMediaItems on an empty player gets the staged pass with its first insert`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle.EMPTY, 0, pass, Random(0))
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndInsert(0, 6)))
    }

    @Test
    fun `a staged pass of another length is ignored`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle.EMPTY, 0, pass, Random(0))
        val inserted = installed.cloneAndInsert(0, 3) as HeadShuffleOrder
        assertEquals(3, inserted.length)
        assertEquals(setOf(0, 1, 2), inserted.shuffle.head.toSet())
    }

    @Test
    fun `the staged pass is used once, and every clone but the removal of everything drops it`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle(listOf(1), 2), 2, pass, Random(0))
        val removedAll = installed.cloneAndRemove(0, 2) as HeadShuffleOrder
        assertEquals(pass, removedAll.staged)
        assertNull((removedAll.cloneAndInsert(0, 6) as HeadShuffleOrder).staged)
        assertNull((installed.cloneAndRemove(0, 1) as HeadShuffleOrder).staged)
        assertNull((installed.cloneAndInsert(1, 1) as HeadShuffleOrder).staged)
        assertNull((installed.cloneAndClear() as HeadShuffleOrder).staged)
    }

    @Test
    fun `an order staged over a playlist changed from outside takes the player's length`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle(listOf(2, 0, 1), 3), 2, pass, Random(0))
        assertEquals(2, installed.length)
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndRemove(0, 2).cloneAndInsert(0, 6)))
    }

    @Test
    fun `the engine's copy changes as ExoPlayer's order does on an insert or a removal`() {
        val copy = HeadShuffle(listOf(2, 0), 3)
        assertEquals(copy.inserted(1, 2), HeadShuffleOrder.afterInsert(copy, 3, 1, 2))
        assertEquals(copy.removed(0, 1), HeadShuffleOrder.afterRemove(copy, 3, 0))
        assertEquals(HeadShuffle(listOf(0, 1), 2), HeadShuffleOrder.afterInsert(HeadShuffle.EMPTY, 0, 0, 2))
    }

    @Test
    fun `an insert past the end goes to the end and a removal past it changes nothing, as in ExoPlayer`() {
        val copy = HeadShuffle(listOf(2, 0), 3)
        assertEquals(HeadShuffle(listOf(2, 0), 5), HeadShuffleOrder.afterInsert(copy, 3, 7, 2))
        assertEquals(copy, HeadShuffleOrder.afterRemove(copy, 3, 3))
    }

    @Test
    fun `over a playlist changed from outside, the copy takes the player's length before an edit`() {
        val copy = HeadShuffle(listOf(2, 0, 1), 3)
        assertEquals(HeadShuffle(emptyList(), 2), HeadShuffleOrder.fitted(copy, 2))
        assertEquals(HeadShuffle(emptyList(), 2), HeadShuffleOrder.afterRemove(copy, 2, 2))
        assertEquals(HeadShuffle(emptyList(), 1), HeadShuffleOrder.afterRemove(copy, 2, 1))
        assertEquals(HeadShuffle(emptyList(), 4), HeadShuffleOrder.afterInsert(copy, 2, 3, 2))
        assertEquals(copy, HeadShuffleOrder.fitted(copy, 3))
    }

    private fun walk(order: ShuffleOrder): List<Int> =
        generateSequence(order.firstIndex.takeIf { it != C.INDEX_UNSET }) { index ->
            order.getNextIndex(index).takeIf { it != C.INDEX_UNSET }
        }.toList()

    private fun walkBack(order: ShuffleOrder): List<Int> =
        generateSequence(order.lastIndex.takeIf { it != C.INDEX_UNSET }) { index ->
            order.getPreviousIndex(index).takeIf { it != C.INDEX_UNSET }
        }.toList()
}
