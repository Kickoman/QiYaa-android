package io.github.kickoman.qiyaa.queue

object QueueWalk {
    fun next(entries: List<QueueEntry>, order: List<Int>, current: Int, repeatHoldsHead: Boolean): Int? {
        val after = after(order, current)
        if (repeatHoldsHead) {
            val isHead = { index: Int -> entries.getOrNull(index)?.part == EntryPart.HEAD }
            return after.firstOrNull(isHead) ?: order.firstOrNull(isHead)
        }
        return after.firstOrNull()
    }

    fun remaining(order: List<Int>, current: Int, repeatHoldsHead: Boolean): Int {
        if (repeatHoldsHead) return Int.MAX_VALUE
        return after(order, current).size + 1
    }

    private fun after(order: List<Int>, current: Int): List<Int> = order.drop(order.indexOf(current) + 1)
}
