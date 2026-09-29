package io.github.kickoman.qiyaa.queue

object PlayOrder {
    fun remainingAfter(order: List<Int>, currentIndex: Int): Int {
        val position = order.indexOf(currentIndex)
        return if (position < 0) order.size else order.size - position - 1
    }
}
