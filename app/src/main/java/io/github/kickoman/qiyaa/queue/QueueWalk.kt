package io.github.kickoman.qiyaa.queue

object QueueWalk {
    fun next(
        entries: List<QueueEntry>,
        order: List<Int>,
        current: Int,
        repeatHoldsHead: Boolean,
        leaving: Set<Int> = emptySet(),
        start: Int? = null,
        from: Int? = null,
    ): Int? {
        val staying = { index: Int -> index !in leaving }
        waiting(entries, current).firstOrNull(staying)?.let { return it }
        startRow(entries, current, start, staying)?.let { return it }
        val after = after(order, from ?: current).filter { staying(it) && it != current }
        if (repeatHoldsHead) {
            val isHead = { index: Int -> staying(index) && entries.getOrNull(index)?.part == EntryPart.HEAD }
            return after.firstOrNull(isHead) ?: order.firstOrNull(isHead)
        }
        return after.firstOrNull { entries.getOrNull(it)?.isSpent != true }
    }

    fun remaining(
        entries: List<QueueEntry>,
        order: List<Int>,
        current: Int,
        repeatHoldsHead: Boolean,
        start: Int? = null,
        from: Int? = null,
    ): Int {
        if (repeatHoldsHead) return Int.MAX_VALUE
        val begin = start?.takeIf { it != current && it in order }
        val ahead = begin?.let { order.drop(order.indexOf(it)).filter { index -> index != current } }
            ?: after(order, from ?: current).filter { it != current }
        val rest = ahead.count { index ->
            entries.getOrNull(index)?.let { it.isSpent || it.isWaiting } != true
        }
        return 1 + waiting(entries, current).size + rest
    }

    private fun startRow(
        entries: List<QueueEntry>,
        current: Int,
        start: Int?,
        staying: (Int) -> Boolean,
    ): Int? {
        if (start == null || start == current) return null
        return (start..entries.lastIndex).firstOrNull { staying(it) && entries[it].part == EntryPart.HEAD }
    }

    private fun waiting(entries: List<QueueEntry>, current: Int): List<Int> =
        entries.indices.filter { it != current && entries[it].isWaiting }

    private fun after(order: List<Int>, current: Int): List<Int> = order.drop(order.indexOf(current) + 1)
}
