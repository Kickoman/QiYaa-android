package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
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
    fun `TR-07 a dislike in a finite queue acts as Next, so the last track stops (TR-04)`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.controller.dislikeAndSkip(track("a"))
        runCurrent()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(QueueEvent.DislikedAndSkipped, harness.events.last())
        harness.controller.dislikeAndSkip(track("b"))
        runCurrent()
        assertEquals(listOf("pause", "seek to 0"), harness.engine.commands.takeLast(2))
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(listOf("dislike a", "dislike b"), harness.source.calls)
    }

    @Test
    fun `TR-07 WAVE-09 a dislike on the last track of a wave acts as Next and asks for more`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1")) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        harness.source.calls.clear()
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.controller.dislikeAndSkip(track("w1"))
        runCurrent()
        assertEquals(listOf("dislike w1", "moreWave S1 [w1]"), harness.source.calls.sorted())
        assertEquals(0, harness.engine.currentIndex)
    }
}
