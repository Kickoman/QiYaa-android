package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeEngine
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.wave
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueTransportTest {
    @Test
    fun `TR-01 within 3 s Previous plays the previous track`() = runTest {
        val harness = loaded(this, "a", "b", "c")
        harness.engine.seekTo(1)
        harness.engine.positionMs = 3_000
        harness.controller.previous()
        assertEquals(0, harness.engine.currentIndex)
    }

    @Test
    fun `TR-01 on the first track Previous goes to the last with repeat and restarts without it`() = runTest {
        val harness = loaded(this, "a", "b", "c")
        harness.engine.startPlaying()
        harness.engine.positionMs = 1_000
        harness.controller.previous()
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(0, harness.engine.currentIndex)
        assertEquals("seek to 0", harness.engine.commands.last())
        assertEquals(listOf("a" to "play-1", "a" to "play-2"), harness.source.reports)
        harness.controller.setRepeat(true)
        harness.engine.positionMs = 1_000
        harness.controller.previous()
        assertEquals(2, harness.engine.currentIndex)
    }

    @Test
    fun `TR-02 after 3 s Previous restarts the current track as a seek, not a new play`() = runTest {
        val harness = loaded(this, "a", "b", "c")
        harness.engine.seekTo(1)
        harness.engine.startPlaying()
        harness.engine.positionMs = 3_001
        harness.controller.previous()
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals("seek to 0", harness.engine.commands.last())
        assertEquals(listOf("b" to "play-1"), harness.source.reports)
    }

    @Test
    fun `TR-02 Previous after 3 s while paused is a seek`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.startPlaying()
        advanceTimeBy(10_000)
        harness.engine.stopPlaying()
        advanceTimeBy(5_000)
        harness.engine.positionMs = 10_000
        harness.controller.previous()
        advanceTimeBy(5_000)
        harness.engine.startPlaying()
        advanceTimeBy(20_000)
        harness.engine.finishTrack()
        runCurrent()
        assertEquals("seek to 0", harness.engine.commands.last())
        assertEquals(listOf("w1" to "play-1", "w2" to "play-2"), harness.source.reports)
        assertEquals(
            listOf(
                "radioStarted S1/user:onyourwave/B1 - 0.0",
                "trackStarted S1/user:onyourwave/B1 w1 0.0",
                "trackFinished S1/user:onyourwave/B1 w1 30.0",
                "trackStarted S1/user:onyourwave/B1 w2 0.0",
            ),
            harness.source.feedback,
        )
    }

    @Test
    fun `TR-03 Next plays the next track in play order`() = runTest {
        val harness = loaded(this, "a", "b", "c")
        harness.controller.setShuffle(true)
        harness.engine.shuffleOrder = listOf(0, 2, 1)
        harness.controller.next()
        assertEquals(2, harness.engine.currentIndex)
    }

    @Test
    fun `TR-03 shuffle switched on starts a pass with the current track, and every track plays once`() =
        runTest {
            val harness = loaded(this, "a", "b", "c", "d")
            harness.engine.seekTo(2)
            harness.controller.setShuffle(true)
            assertEquals(2, harness.engine.playOrder().first())
            val played = mutableListOf(harness.engine.currentIndex)
            repeat(3) {
                harness.controller.next()
                played += harness.engine.currentIndex
            }
            assertEquals(setOf(0, 1, 2, 3), played.toSet())
            assertFalse(harness.engine.hasNext())
        }

    @Test
    fun `TR-03 with shuffle on, a new queue's pass starts with its first track`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.setShuffle(true)
        harness.controller.loadSource("Liked") { tracks("a", "b", "c") }
        runCurrent()
        assertTrue(harness.engine.shuffleEnabled)
        assertEquals(0, harness.engine.playOrder().first())
        assertEquals(setOf(0, 1, 2), harness.engine.playOrder().toSet())
    }

    @Test
    fun `TR-04 Next on the last track without repeat stops there`() = runTest {
        val harness = loaded(this, "a", "b")
        harness.engine.seekTo(1)
        harness.engine.commands.clear()
        harness.controller.next()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(listOf("pause", "seek to 0"), harness.engine.commands)
        assertEquals(listOf("a", "b"), harness.engine.ids())
    }

    @Test
    fun `TR-05 Next on the last track with repeat plays the first`() = runTest {
        val harness = loaded(this, "a", "b")
        harness.controller.setRepeat(true)
        harness.engine.seekTo(1)
        harness.controller.next()
        assertEquals(0, harness.engine.currentIndex)
    }

    @Test
    fun `WAVE-09 Next at the end of a wave asks for more and says it is loading`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1")) }
        harness.source.onMoreWave = { _, _ -> throw IllegalStateException("offline") }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        harness.engine.finishTrack()
        runCurrent()
        val attempts = harness.source.calls.count { it.startsWith("moreWave") }
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.controller.next()
        runCurrent()
        assertEquals(attempts + 1, harness.source.calls.count { it.startsWith("moreWave") })
        assertEquals(QueueEvent.LoadingMore, harness.events.last())
    }

    @Test
    fun `a new player gets the same queue, paused on the same track and position`() = runTest {
        val harness = loaded(this, "a", "b", "c")
        harness.controller.setRepeat(true)
        harness.engine.seekTo(1)
        harness.engine.positionMs = 42_000
        harness.controller.detach(harness.engine)

        val next = FakeEngine(harness.controller)
        harness.controller.attach(next)
        assertEquals(listOf("a", "b", "c"), next.ids())
        assertEquals("set [a, b, c] play=false at 1:42000", next.commands.last { it.startsWith("set") })
        assertTrue("the user's repeat comes back", next.repeatEnabled)
        assertEquals(listOf("a", "b", "c"), harness.controller.state.value.tracks.map { it.id })
    }

    @Test
    fun `a source picked before the player is ready is applied when it attaches`() = runTest {
        val harness = QueueHarness(this, attached = false)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        assertEquals(listOf("a", "b"), harness.controller.state.value.tracks.map { it.id })
        assertEquals(listOf(QueueEvent.SourceLoaded("Liked", 2)), harness.events)
        harness.controller.attach(harness.engine)
        assertEquals("set [a, b] play=true", harness.engine.commands.last { it.startsWith("set") })
    }

    @Test
    fun `clearing while no player is attached leaves nothing to restore`() = runTest {
        val harness = loaded(this, "a", "b")
        harness.controller.detach(harness.engine)
        harness.controller.clear()
        val next = FakeEngine(harness.controller)
        harness.controller.attach(next)
        assertEquals(emptyList<String>(), next.commands)
        assertEquals(QueueState(), harness.controller.state.value)
    }

    private fun loaded(scope: TestScope, vararg ids: String): QueueHarness {
        val harness = QueueHarness(scope)
        harness.controller.loadSource("Liked") { tracks(*ids) }
        scope.runCurrent()
        harness.events.clear()
        harness.engine.commands.clear()
        return harness
    }
}
