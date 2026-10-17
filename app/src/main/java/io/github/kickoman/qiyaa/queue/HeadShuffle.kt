package io.github.kickoman.qiyaa.queue

import kotlin.random.Random

data class HeadShuffle(val head: List<Int>, val length: Int) {
    init {
        require(length >= 0) { "The length of a shuffle order is $length" }
        require(head.all { it in 0 until length } && head.toSet().size == head.size) {
            "The head $head is not a set of indices below $length"
        }
    }

    val order: List<Int> = head + (0 until length).filterNot(head.toSet()::contains)

    fun inserted(at: Int, count: Int): HeadShuffle {
        require(at in 0..length && count >= 0) { "Cannot insert $count at $at into a length of $length" }
        if (length == 0) return HeadShuffle((0 until count).toList(), count)
        return HeadShuffle(head.map { if (it >= at) it + count else it }, length + count)
    }

    fun removed(from: Int, until: Int): HeadShuffle {
        require(from in 0..until && until <= length) {
            "Cannot remove $from until $until from a length of $length"
        }
        val count = until - from
        val kept = head.filterNot { it in from until until }.map { if (it >= until) it - count else it }
        return HeadShuffle(kept, length - count)
    }

    companion object {
        val EMPTY = HeadShuffle(emptyList(), 0)

        fun pass(headSize: Int, length: Int, first: Int, random: Random): HeadShuffle {
            val size = headSize.coerceIn(0, length)
            val rest = (0 until size).filter { it != first }.shuffled(random)
            return HeadShuffle(if (first in 0 until size) listOf(first) + rest else rest, length)
        }
    }
}
