package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueEditingTest {
    @Test
    fun `removing selected tracks removes them from the player from the end and clears the selection`() =
        runTest {
            val harness = QueueHarness(this)
            harness.controller.loadSource("Liked") { tracks("a", "b", "c", "d") }
            runCurrent()
            harness.controller.toggleSelected(1)
            harness.controller.toggleSelected(3)
            harness.controller.removeIndices(harness.controller.state.value.selected)
            assertEquals(listOf("remove 3", "remove 1"), harness.engine.commands.takeLast(2))
            assertEquals(listOf("a", "c"), harness.controller.state.value.tracks.map { it.id })
            assertEquals(emptySet<Int>(), harness.controller.state.value.selected)
        }

    @Test
    fun `select all toggles between every track and none`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.controller.selectAllOrNone()
        assertEquals(setOf(0, 1), harness.controller.state.value.selected)
        harness.controller.selectAllOrNone()
        assertEquals(emptySet<Int>(), harness.controller.state.value.selected)
    }

    @Test
    fun `clear stops the player and empties the queue`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.controller.clear()
        assertEquals(listOf("stop", "clear"), harness.engine.commands.takeLast(2))
        assertEquals(QueueState(), harness.controller.state.value)
    }

    @Test
    fun `syncFromPlayer takes the tracks from the player when the counts differ`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b", "c") }
        runCurrent()
        harness.engine.removeAt(2)
        harness.controller.syncFromPlayer()
        assertEquals(listOf("a", "b"), harness.controller.state.value.tracks.map { it.id })
    }

    @Test
    fun `toggling a like flips it and reports the new state`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.toggleLike(track("a"))
        runCurrent()
        assertTrue(harness.source.isLiked("a"))
        assertEquals(QueueEvent.LikeChanged(liked = true), harness.events.last())
        harness.controller.toggleLike(track("a"))
        runCurrent()
        assertEquals(QueueEvent.LikeChanged(liked = false), harness.events.last())
    }

    @Test
    fun `TR-07 a dislike in a finite queue skips, and on the last track stops`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.controller.dislikeAndSkip(track("a"))
        runCurrent()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(QueueEvent.DislikedAndSkipped, harness.events.last())
        harness.controller.dislikeAndSkip(track("b"))
        runCurrent()
        assertEquals("stop", harness.engine.commands.last())
        assertEquals(listOf("dislike a", "dislike b"), harness.source.calls)
    }

    @Test
    fun `a dislike on the last track of a wave neither skips nor stops`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1")) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        harness.engine.commands.clear()
        harness.controller.dislikeAndSkip(track("w1"))
        runCurrent()
        assertEquals(emptyList<String>(), harness.engine.commands)
    }
}
