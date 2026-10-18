package io.github.kickoman.qiyaa.queue

import kotlin.random.Random

object RandomTrack {
    fun draw(head: List<Int>, current: Int, random: Random): Int? {
        if (head.size < 2) return null
        val others = head.filter { it != current }
        return others[random.nextInt(others.size)]
    }

    fun previous(head: List<Int>, current: Int, repeat: Boolean): Int {
        val position = head.indexOf(current)
        require(position >= 0) { "The track $current is not in the head $head" }
        return when {
            position > 0 -> head[position - 1]
            repeat -> head.last()
            else -> current
        }
    }
}
