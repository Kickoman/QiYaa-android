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
    fun `SHUF-05 a staged draw is the order's next of the current track, also behind waiting items`() {
        val drawn = HeadShuffle.pass(listOf(0, 1, 2, 3, 4), 6, listOf(2, 5, 4), Random(0))
        val order = HeadShuffleOrder(drawn, Random(0))
        assertEquals(2, order.firstIndex)
        assertEquals(5, order.getNextIndex(2))
        assertEquals(4, order.getNextIndex(5))
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
    fun `remove all then insert as many gets the staged pass, as the old setMediaItems did`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle(listOf(1, 0), 3), 3, pass, Random(0))
        assertEquals(3, installed.length)
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndRemove(0, 3).cloneAndInsert(0, 6)))
    }

    @Test
    fun `the first insert into an empty order gets the staged pass, as the old setMediaItems did`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle.EMPTY, 0, pass, Random(0))
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndInsert(0, 6)))
    }

    @Test
    fun `setMediaItems gets exactly the staged pass through cloneAndSet, whatever the start index`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle(listOf(1, 0), 3), 3, pass, Random(0))
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndSet(6, C.INDEX_UNSET)))
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndSet(6, 4)))
    }

    @Test
    fun `setMediaItems or a first insert on an empty player gets the staged pass through cloneAndSet`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle.EMPTY, 0, pass, Random(0))
        assertEquals(listOf(3, 1, 0, 2, 4, 5), walk(installed.cloneAndSet(6, C.INDEX_UNSET)))
    }

    @Test
    fun `the staged pass survives one cloneAndSet, and a second of the same length gives a fresh pass`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle(listOf(1, 0), 3), 3, pass, Random(5))
        val first = installed.cloneAndSet(6, C.INDEX_UNSET) as HeadShuffleOrder
        assertEquals(pass, first.shuffle)
        assertNull(first.staged)
        val second = first.cloneAndSet(6, C.INDEX_UNSET) as HeadShuffleOrder
        assertEquals(freshPass(6, seed = 5), second.shuffle)
        assertEquals(6, second.shuffle.head.size)
        assertNull(second.staged)
    }

    @Test
    fun `cloneAndSet takes neither a staged pass of another length nor a staged insert`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle.EMPTY, 0, pass, Random(3))
        val other = installed.cloneAndSet(3, C.INDEX_UNSET) as HeadShuffleOrder
        assertEquals(freshPass(3, seed = 3), other.shuffle)
        assertNull(other.staged)
        val copy = HeadShuffle(listOf(2, 0, 1), 3)
        val inserting = HeadShuffleOrder.staging(copy, 3, copy.insertedAfter(1, 1), Random(4), at = 1)
        val set = inserting.cloneAndSet(4, C.INDEX_UNSET) as HeadShuffleOrder
        assertEquals(freshPass(4, seed = 4), set.shuffle)
        assertNull(set.staged)
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

    @Test
    fun `addMediaItems gets the staged insert once, only for its index and count, with no staging left`() {
        val copy = HeadShuffle(listOf(2, 0, 1), 3)
        val spliced = copy.insertedAfter(1, 1)
        val installed = HeadShuffleOrder.staging(copy, 3, spliced, Random(0), at = 1)
        val inserted = installed.cloneAndInsert(1, 1) as HeadShuffleOrder
        assertEquals(spliced, inserted.shuffle)
        assertEquals(listOf(3, 0, 1, 2), walk(inserted))
        assertNull(inserted.staged)
        assertEquals(copy.inserted(0, 1), (installed.cloneAndInsert(0, 1) as HeadShuffleOrder).shuffle)
        assertEquals(copy.inserted(1, 2), (installed.cloneAndInsert(1, 2) as HeadShuffleOrder).shuffle)
        assertNull((installed.cloneAndRemove(2, 3) as HeadShuffleOrder).staged)
    }

    @Test
    fun `an insert at another index does not take the staged one, even after everything was removed`() {
        val installed = HeadShuffleOrder.staging(HeadShuffle(listOf(1, 0), 2), 2, pass, Random(0), at = 2)
        val inserted = installed.cloneAndRemove(0, 2).cloneAndInsert(0, 6) as HeadShuffleOrder
        assertEquals(6, inserted.length)
        assertNull(inserted.staged)
        assertEquals(setOf(0, 1, 2, 3, 4, 5), inserted.shuffle.head.toSet())
    }

    @Test
    fun `the engine's copy after a splice follows the neighbour, with an index past the end at the end`() {
        val copy = HeadShuffle(listOf(2, 0), 3)
        assertEquals(copy.insertedAfter(1, 2), HeadShuffleOrder.afterSplice(copy, 3, 1, 2))
        assertEquals(copy.insertedAfter(3, 2), HeadShuffleOrder.afterSplice(copy, 3, 7, 2))
        assertEquals(HeadShuffle(emptyList(), 4), HeadShuffleOrder.afterSplice(copy, 2, 3, 2))
        assertEquals(HeadShuffle(listOf(0, 1), 2), HeadShuffleOrder.afterSplice(HeadShuffle.EMPTY, 0, 0, 2))
    }

    @Test
    fun `QUAL-05 replaceMediaItem as ExoPlayer clones it, insert then remove, gives back the same order`() {
        for (installed in listOf(pass, HeadShuffle(emptyList(), 4))) {
            for (index in 0 until installed.length) {
                val replacing = HeadShuffleOrder.replacing(installed, installed.length, index, Random(0))
                assertEquals(installed, replacing.shuffle)
                val replaced = replacing.cloneAndInsert(index + 1, 1).cloneAndRemove(index, index + 1)
                assertEquals("$installed at $index", installed, (replaced as HeadShuffleOrder).shuffle)
                assertEquals(installed.order, walk(replaced))
            }
        }
    }

    @Test
    fun `QUAL-05 the order after a replacement carries no staging`() {
        val replacing = HeadShuffleOrder.replacing(pass, pass.length, 1, Random(0))
        assertEquals(pass.insertedAfter(2, 1), replacing.staged)
        assertEquals(2, replacing.stagedAt)
        val inserted = replacing.cloneAndInsert(2, 1) as HeadShuffleOrder
        assertNull(inserted.staged)
        assertNull((inserted.cloneAndRemove(1, 2) as HeadShuffleOrder).staged)
    }

    @Test
    fun `QUAL-05 between ExoPlayer's two clones the old next track still follows the current one`() {
        val walk = pass.order
        for ((position, current) in walk.withIndex()) {
            val next = walk.getOrNull(position + 1) ?: walk.first()
            val replacing = HeadShuffleOrder.replacing(pass, pass.length, next, Random(0))
            val inserted = replacing.cloneAndInsert(next + 1, 1)
            val shifted = if (current > next) current + 1 else current
            val following = inserted.getNextIndex(shifted).takeIf { it != C.INDEX_UNSET }
                ?: inserted.firstIndex
            assertEquals("after $current", next, following)
            assertEquals("after $current", next + 1, inserted.getNextIndex(next))
        }
    }

    @Test
    fun `QUAL-05 cloneAndSet never takes the order staged for a replacement`() {
        val replacing = HeadShuffleOrder.replacing(pass, pass.length, 0, Random(2))
        val set = replacing.cloneAndSet(pass.length + 1, C.INDEX_UNSET) as HeadShuffleOrder
        assertEquals(freshPass(pass.length + 1, seed = 2), set.shuffle)
        assertNull(set.staged)
    }

    @Test
    fun `QUAL-05 a replacement over a playlist changed from outside takes the player's length`() {
        val replacing = HeadShuffleOrder.replacing(HeadShuffle(listOf(2, 0, 1), 3), 2, 0, Random(0))
        assertEquals(2, replacing.length)
        val replaced = replacing.cloneAndInsert(1, 1).cloneAndRemove(0, 1) as HeadShuffleOrder
        assertEquals(HeadShuffle(emptyList(), 2), replaced.shuffle)
    }

    private fun freshPass(count: Int, seed: Int): HeadShuffle {
        val empty = HeadShuffleOrder(HeadShuffle.EMPTY, Random(seed))
        return (empty.cloneAndInsert(0, count) as HeadShuffleOrder).shuffle
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
