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
        if (order.isNotEmpty()) {
            return HeadShuffleOrder(shuffle.inserted(insertionIndex, insertionCount), random)
        }
        val pass =
            staged?.takeIf { it.length == insertionCount }
                ?: HeadShuffle.pass(insertionCount, insertionCount, C.INDEX_UNSET, random)
        return HeadShuffleOrder(pass, random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = shuffle.removed(indexFrom, indexToExclusive)
        return HeadShuffleOrder(removed, random, staged.takeIf { removed.length == 0 })
    }

    override fun cloneAndClear(): ShuffleOrder = HeadShuffleOrder(HeadShuffle.EMPTY, random)

    companion object {
        fun staging(
            installed: HeadShuffle,
            itemCount: Int,
            pass: HeadShuffle,
            random: Random,
        ): HeadShuffleOrder {
            val current = installed.takeIf { it.length == itemCount } ?: HeadShuffle(emptyList(), itemCount)
            return HeadShuffleOrder(current, random, pass)
        }
    }
}
