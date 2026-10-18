package io.github.kickoman.qiyaa.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedPlaysTest {
    private val plays = CachedPlays(process = "0000abcd")

    @Test
    fun `TR-09 QUAL-08 each play of a track gets its own disk key`() {
        val first = plays.newKey()
        val second = plays.newKey()
        plays.opened(first, TRACK_A)
        plays.opened(second, TRACK_A)

        assertEquals("play-0000abcd-1", first)
        assertEquals("play-0000abcd-2", second)
        assertNotEquals(first, CachedPlays(process = "0000abce").newKey())
        assertTrue(Regex("play-[0-9a-f]{8}-1").matches(CachedPlays().newKey()))
    }

    @Test
    fun `TR-09 a track change keeps the plays of the current and the next track and drops the rest`() {
        val a = openedKey(TRACK_A)
        val b = openedKey(TRACK_B)
        val c = openedKey(TRACK_C)

        assertEquals(setOf(a), plays.toDrop(setOf(a, b, c), keep = listOf(TRACK_B, TRACK_C)))
        assertEquals(setOf(b), plays.toDrop(setOf(b, c), keep = listOf(TRACK_C)))
    }

    @Test
    fun `a play the disk holds but this process never opened is dropped even when its track is current`() {
        val a = openedKey(TRACK_A)
        val leftover = "play-0000beef-1"
        val unopened = plays.newKey()

        assertEquals(
            setOf(leftover, unopened),
            plays.toDrop(setOf(a, leftover, unopened), keep = listOf(TRACK_A)),
        )
    }

    @Test
    fun `a one-track repeat keeps the two newest laps`() {
        val laps = List(10) { openedKey(TRACK_A) }

        val dropped = plays.toDrop(laps.toSet(), keep = listOf(TRACK_A, TRACK_A))

        assertEquals(laps.take(8).toSet(), dropped)
    }

    @Test
    fun `two rows of one track keep the playing play and the next one`() {
        val earlier = openedKey(TRACK_A)
        val playing = openedKey(TRACK_A)
        val next = openedKey(TRACK_A)

        assertEquals(
            setOf(earlier),
            plays.toDrop(setOf(earlier, playing, next), keep = listOf(TRACK_A, TRACK_A)),
        )
    }

    @Test
    fun `an emptied playlist drops every play, also keys it never knew`() {
        val a = openedKey(TRACK_A)
        val b = openedKey(TRACK_B)

        assertEquals(setOf(a, b, "leftover"), plays.toDrop(setOf(a, b, "leftover"), keep = emptyList()))
    }

    @Test
    fun `a dropped play that opens again is kept again`() {
        val a = openedKey(TRACK_A)
        plays.toDrop(setOf(a), keep = emptyList())

        plays.opened(a, TRACK_A)

        assertEquals(emptySet<String>(), plays.toDrop(setOf(a), keep = listOf(TRACK_A)))
    }

    @Test
    fun `smoke test of plays opened on four loading threads while toDrop runs on the cache thread`() {
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val loaders =
            List(4) { i ->
                thread {
                    start.await(5, TimeUnit.SECONDS)
                    try {
                        repeat(PLAYS_PER_THREAD) { plays.opened(plays.newKey(), "qiyaa://track/$i") }
                    } catch (failed: Throwable) {
                        failure.set(failed)
                    }
                }
            }
        val dropper =
            thread {
                start.await(5, TimeUnit.SECONDS)
                try {
                    repeat(PLAYS_PER_THREAD) {
                        plays.toDrop(setOf("leftover"), keep = listOf("qiyaa://track/0"))
                    }
                } catch (failed: Throwable) {
                    failure.set(failed)
                }
            }

        start.countDown()
        (loaders + dropper).forEach { it.join(10_000) }

        assertNull(failure.get())
        val all = (1..4 * PLAYS_PER_THREAD).map { "play-0000abcd-$it" }.toSet()
        assertEquals(all.size - 2, plays.toDrop(all, keep = listOf("qiyaa://track/0")).size)
    }

    private fun openedKey(uri: String): String = plays.newKey().also { plays.opened(it, uri) }

    private companion object {
        const val TRACK_A = "qiyaa://track/1"
        const val TRACK_B = "qiyaa://track/2"
        const val TRACK_C = "qiyaa://track/3?r=1"
        const val PLAYS_PER_THREAD = 1_000
    }
}
