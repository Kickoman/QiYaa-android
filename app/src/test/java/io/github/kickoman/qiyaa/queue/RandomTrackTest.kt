package io.github.kickoman.qiyaa.queue

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomTrackTest {
    @Test
    fun `SHUF-06 a draw is another head track, each with the same chance`() {
        val random = Random(0)
        val counts = (1..30_000).groupingBy { RandomTrack.draw(listOf(0, 2, 3, 5), 2, random) }.eachCount()
        assertEquals(setOf(0, 3, 5), counts.keys)
        for ((index, count) in counts) assertTrue("$index came $count times", count in 9_700..10_300)
    }

    @Test
    fun `SHUF-06 a head of fewer than two tracks draws nothing`() {
        assertNull(RandomTrack.draw(emptyList(), -1, Random(0)))
        assertNull(RandomTrack.draw(listOf(3), 3, Random(0)))
        assertNull(RandomTrack.draw(listOf(3), 1, Random(0)))
    }

    @Test
    fun `SHUF-06 with the current track outside the head any head track may come`() {
        val random = Random(0)
        assertEquals(setOf(1, 4), (1..100).map { RandomTrack.draw(listOf(1, 4), 2, random) }.toSet())
        assertEquals(setOf(1, 4), (1..100).map { RandomTrack.draw(listOf(1, 4), -1, random) }.toSet())
    }

    @Test
    fun `SHUF-07 Previous is the head track before in source order, on the first the last with repeat`() {
        val head = listOf(0, 2, 3, 5)
        assertEquals(2, RandomTrack.previous(head, 3, repeat = false))
        assertEquals(3, RandomTrack.previous(head, 5, repeat = true))
        assertEquals(0, RandomTrack.previous(head, 0, repeat = false))
        assertEquals(5, RandomTrack.previous(head, 0, repeat = true))
    }

    @Test
    fun `SHUF-07 Previous of a track outside the head is refused with the track and the head`() {
        val failed = assertThrows(IllegalArgumentException::class.java) {
            RandomTrack.previous(listOf(0, 2), 1, repeat = false)
        }
        assertEquals("The track 1 is not in the head [0, 2]", failed.message)
    }
}
