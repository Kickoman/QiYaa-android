package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import io.github.kickoman.qiyaa.queue.HeadShuffle
import kotlin.random.Random

@UnstableApi
class HeadShuffleOrder(
    val shuffle: HeadShuffle,
    private val random: Random,
    val staged: HeadShuffle? = null,
    val stagedAt: Int = 0,
) : ShuffleOrder {
    private val order = shuffle.order.toIntArray()
    private val positions =
        IntArray(order.size).apply { order.forEachIndexed { at, index -> this[index] = at } }

    override fun getLength(): Int = order.size

    override fun getNextIndex(index: Int): Int = order.getOrNull(positions[index] + 1) ?: C.INDEX_UNSET

    override fun getPreviousIndex(index: Int): Int = order.getOrNull(positions[index] - 1) ?: C.INDEX_UNSET

    override fun getLastIndex(): Int = order.lastOrNull() ?: C.INDEX_UNSET

    override fun getFirstIndex(): Int = order.firstOrNull() ?: C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val ready = staged?.takeIf { insertionIndex == stagedAt && it.length == order.size + insertionCount }
        val inserted =
            when {
                ready != null -> ready
                order.isNotEmpty() -> shuffle.inserted(insertionIndex, insertionCount)
                else -> HeadShuffle.pass(insertionCount, insertionCount, C.INDEX_UNSET, random)
            }
        return HeadShuffleOrder(inserted, random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = shuffle.removed(indexFrom, indexToExclusive)
        return HeadShuffleOrder(removed, random, staged.takeIf { removed.length == 0 }, stagedAt)
    }

    override fun cloneAndClear(): ShuffleOrder = HeadShuffleOrder(HeadShuffle.EMPTY, random)

    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder {
        val ready = staged?.takeIf { stagedAt == 0 && it.length == insertionCount }
            ?: return cloneAndClear().cloneAndInsert(0, insertionCount)
        return HeadShuffleOrder(ready, random)
    }

    companion object {
        fun staging(
            installed: HeadShuffle,
            itemCount: Int,
            pass: HeadShuffle,
            random: Random,
            at: Int = 0,
        ): HeadShuffleOrder = HeadShuffleOrder(fitted(installed, itemCount), random, pass, at)

        fun fitted(copy: HeadShuffle, itemCount: Int): HeadShuffle =
            copy.takeIf { it.length == itemCount } ?: HeadShuffle(emptyList(), itemCount)

        fun afterInsert(copy: HeadShuffle, itemCount: Int, index: Int, count: Int): HeadShuffle =
            fitted(copy, itemCount).inserted(index.coerceAtMost(itemCount), count)

        fun afterSplice(copy: HeadShuffle, itemCount: Int, index: Int, count: Int): HeadShuffle =
            fitted(copy, itemCount).insertedAfter(index.coerceAtMost(itemCount), count)

        fun afterRemove(copy: HeadShuffle, itemCount: Int, index: Int): HeadShuffle {
            val current = fitted(copy, itemCount)
            return if (index >= itemCount) current else current.removed(index, index + 1)
        }

        fun replacing(installed: HeadShuffle, itemCount: Int, index: Int, random: Random): HeadShuffleOrder {
            val current = fitted(installed, itemCount)
            return staging(current, itemCount, current.insertedAfter(index + 1, 1), random, at = index + 1)
        }
    }
}
