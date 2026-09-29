package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

object PlayOrder {
    fun remainingAfter(order: List<Int>, currentIndex: Int): Int {
        val position = order.indexOf(currentIndex)
        return if (position < 0) order.size else order.size - position - 1
    }

    fun of(timeline: Timeline, shuffle: Boolean): List<Int> {
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return order
    }
}
