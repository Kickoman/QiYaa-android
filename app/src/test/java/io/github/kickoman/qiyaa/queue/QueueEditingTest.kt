package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.wave
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `the entry the engine reports is the entry in state after removing rows above, at and below it`() =
        runTest {
            val harness = QueueHarness(this)
            harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("a", "b", "c")) }
            val more = ArrayDeque(listOf(WaveBatch("S1", "B2", tracks("d", "e", "f"))))
            harness.source.onMoreWave =
                { _, _ -> more.removeFirstOrNull() ?: CompletableDeferred<WaveBatch>().await() }
            harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
            runCurrent()
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("a", "b", "c", "d", "e", "f"), harness.engine.ids())
            assertEquals(2, harness.engine.currentIndex)
            harness.source.feedback.clear()

            harness.controller.removeIndices(setOf(0, 2, 5))
            runCurrent()

            assertEquals(listOf("b", "d", "e"), harness.engine.ids())
            assertEquals(harness.engine.ids(), harness.controller.state.value.tracks.map { it.id })
            assertEquals(1, harness.engine.currentIndex)
            assertEquals(
                listOf("skip S1/user:onyourwave/B1 c 0.0", "trackStarted S1/user:onyourwave/B2 d 0.0"),
                harness.source.feedback,
            )
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
        harness.controller.dislike(track("a"))
        runCurrent()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(QueueEvent.DislikedAndSkipped, harness.events.last())
        harness.controller.dislike(track("b"))
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
        harness.controller.dislike(track("w1"))
        runCurrent()
        assertEquals(listOf("dislike w1", "moreWave S1 [w1]"), harness.source.calls.sorted())
        assertEquals(0, harness.engine.currentIndex)
    }

    @Test
    fun `TR-10 a dislike of a track that is not current sends only the dislike, no Next and no wave skip`() =
        runTest {
            val harness = wave(this, "w1", "w2", "w3")
            harness.engine.startPlaying()
            advanceTimeBy(3_000)
            runCurrent()
            val commands = harness.engine.commands.toList()
            val feedback = harness.source.feedback.toList()
            val reports = harness.source.reports.toList()
            harness.source.calls.clear()
            harness.controller.dislike(harness.engine.tracks[2])
            runCurrent()
            assertEquals(listOf("dislike w3"), harness.source.calls)
            assertEquals(QueueEvent.Disliked, harness.events.last())
            assertFalse(QueueEvent.DislikedAndSkipped in harness.events)
            assertEquals(0, harness.engine.currentIndex)
            assertTrue(harness.engine.playing)
            assertEquals(commands, harness.engine.commands)
            assertEquals(feedback, harness.source.feedback)
            assertEquals(reports, harness.source.reports)
            assertEquals(listOf("w1", "w2", "w3"), harness.controller.state.value.tracks.map { it.id })
        }

    @Test
    fun `TR-10 TR-07 the current track disliked from its own line or another copy dislikes and skips`() =
        runTest {
            val harness = QueueHarness(this)
            harness.controller.loadSource("Liked") { tracks("a", "b", "a", "c") }
            runCurrent()
            val lines = harness.controller.state.value.tracks
            harness.controller.dislike(lines[2])
            runCurrent()
            assertEquals(1, harness.engine.currentIndex)
            assertEquals(QueueEvent.DislikedAndSkipped, harness.events.last())
            harness.controller.dislike(lines[1])
            runCurrent()
            assertEquals(2, harness.engine.currentIndex)
            assertEquals(QueueEvent.DislikedAndSkipped, harness.events.last())
            assertEquals(listOf("dislike a", "dislike b"), harness.source.calls)
        }

    @Test
    fun `TR-10 a paused current track is current too, so its dislike skips`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.engine.startPlaying()
        harness.engine.pause()
        harness.controller.dislike(track("a"))
        runCurrent()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(QueueEvent.DislikedAndSkipped, harness.events.last())
        assertEquals(listOf("dislike a"), harness.source.calls)
    }

    @Test
    fun `TR-10 a track that stopped being current before the action only gets the dislike`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b", "c") }
        runCurrent()
        harness.engine.startPlaying()
        val picked = harness.controller.state.value.tracks[0]
        harness.engine.finishTrack()
        val commands = harness.engine.commands.toList()
        harness.controller.dislike(picked)
        runCurrent()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(commands, harness.engine.commands)
        assertEquals(QueueEvent.Disliked, harness.events.last())
        assertEquals(listOf("dislike a"), harness.source.calls)
    }

    @Test
    fun `a like from the playlist sends the state the user saw, even if the liked set changed meanwhile`() =
        runTest {
            val harness = QueueHarness(this)
            harness.source.liked += "a"
            harness.controller.setLiked(track("a"), liked = true)
            runCurrent()
            assertEquals(QueueEvent.LikeChanged(liked = true), harness.events.last())
            harness.source.liked -= "a"
            harness.controller.setLiked(track("a"), liked = false)
            runCurrent()
            assertEquals(QueueEvent.LikeChanged(liked = false), harness.events.last())
            assertEquals(listOf("setLiked a true", "setLiked a false"), harness.source.calls)
        }

    @Test
    fun `a like from the notification goes to the current track`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.engine.seekTo(1)
        harness.controller.likeCurrent()
        runCurrent()
        assertEquals(listOf("setLiked b true"), harness.source.calls)
        assertEquals(QueueEvent.LikeChanged(liked = true), harness.events.last())
    }

    @Test
    fun `a like or a dislike from the notification does nothing with an empty queue or without a player`() =
        runTest {
            val empty = QueueHarness(this)
            empty.controller.likeCurrent()
            empty.controller.dislikeCurrent()
            val detached = QueueHarness(this, attached = false)
            detached.controller.loadSource("Liked") { tracks("a") }
            runCurrent()
            detached.controller.likeCurrent()
            detached.controller.dislikeCurrent()
            runCurrent()
            assertEquals(emptyList<String>(), empty.source.calls + detached.source.calls)
        }
}
