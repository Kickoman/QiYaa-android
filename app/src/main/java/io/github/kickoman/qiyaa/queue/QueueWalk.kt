package io.github.kickoman.qiyaa.queue

object QueueWalk {
    fun next(
        entries: List<QueueEntry>,
        order: List<Int>,
        current: Int,
        repeatHoldsHead: Boolean,
        leaving: Set<Int> = emptySet(),
    ): Int? {
        val staying = { index: Int -> index !in leaving }
        waiting(entries, current).firstOrNull(staying)?.let { return it }
        val after = after(order, current).filter(staying)
        if (repeatHoldsHead) {
            val isHead = { index: Int -> staying(index) && entries.getOrNull(index)?.part == EntryPart.HEAD }
            return after.firstOrNull(isHead) ?: order.firstOrNull(isHead)
        }
        return after.firstOrNull { entries.getOrNull(it)?.isSpent != true }
    }

    fun remaining(entries: List<QueueEntry>, order: List<Int>, current: Int, repeatHoldsHead: Boolean): Int {
        if (repeatHoldsHead) return Int.MAX_VALUE
        val rest = after(order, current).count { index ->
            entries.getOrNull(index)?.let { it.isSpent || it.isWaiting } != true
        }
        return 1 + waiting(entries, current).size + rest
    }

    private fun waiting(entries: List<QueueEntry>, current: Int): List<Int> =
        entries.indices.filter { it != current && entries[it].isWaiting }

    private fun after(order: List<Int>, current: Int): List<Int> = order.drop(order.indexOf(current) + 1)
}
