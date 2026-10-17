package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeEngine
import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePersistenceTest {
    @Test
    fun `a new queue is saved`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked", sourceId = "liked") { tracks("a", "b") }
        runCurrent()
        val saved = harness.store.snapshot()!!
        assertEquals(listOf("a", "b"), saved.tracks.map { it.id })
        assertEquals("Liked", saved.title)
        assertEquals("liked", saved.sourceId)
        assertEquals(0, saved.index)
    }

    @Test
    fun `a pause saves the track and the position`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.engine.seekTo(1)
        harness.engine.startPlaying()
        harness.engine.positionMs = 42_000
        harness.engine.stopPlaying()
        runCurrent()
        val saved = harness.store.snapshot()!!
        assertEquals(1, saved.index)
        assertEquals(42_000L, saved.positionMs)
    }

    @Test
    fun `a seek saves the new position`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.engine.seekTo(1)
        harness.engine.startPlaying()
        runCurrent()
        harness.engine.seekToPosition(42_000)
        runCurrent()
        val saved = harness.store.snapshot()!!
        assertEquals(1, saved.index)
        assertEquals(42_000L, saved.positionMs)
    }

    @Test
    fun `clearing the queue saves an empty queue`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a") }
        runCurrent()
        harness.controller.clear()
        runCurrent()
        assertEquals(emptyList<String>(), harness.store.snapshot()!!.tracks)
    }

    @Test
    fun `a new process restores the saved queue, paused on the same track and position`() = runTest {
        val first = QueueHarness(this)
        first.controller.loadSource("Liked", sourceId = "liked") { tracks("a", "b", "c") }
        runCurrent()
        first.controller.setRepeat(true)
        first.engine.seekTo(2)
        first.engine.positionMs = 7_000
        first.engine.startPlaying()
        first.engine.stopPlaying()
        runCurrent()

        val second = QueueHarness(this, attached = false, store = FakeQueueStore(first.store.text))
        assertEquals(listOf("a", "b", "c"), second.controller.state.value.tracks.map { it.id })
        assertEquals("Liked", second.controller.state.value.title)
        assertEquals("liked", second.controller.state.value.activeSourceId)
        second.controller.attach(second.engine)
        assertEquals(
            "set [a, b, c] play=false at 2:7000",
            second.engine.commands.last {
                it.startsWith("set")
            },
        )
        assertTrue("the user's repeat comes back", second.engine.repeatEnabled)
    }

    @Test
    fun `with shuffle on, a restored queue starts a new pass from the restored track`() = runTest {
        val first = QueueHarness(this)
        first.controller.loadSource("Liked") { tracks("a", "b", "c", "d", "e") }
        runCurrent()
        first.controller.setShuffle(true)
        first.engine.seekTo(3)
        first.engine.startPlaying()
        first.engine.stopPlaying()
        runCurrent()

        val second = QueueHarness(this, attached = false, store = FakeQueueStore(first.store.text))
        second.controller.attach(second.engine)
        assertTrue(second.engine.shuffleEnabled)
        assertEquals(3, second.engine.currentIndex)
        assertEquals(listOf(3, 0, 1, 2, 4), second.engine.playOrder())
    }

    @Test
    fun `TR-02 Previous on a restored queue at 42 s seeks to 0, and Play reports the track once`() = runTest {
        val first = QueueHarness(this)
        first.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        first.engine.seekTo(1)
        first.engine.positionMs = 42_000
        first.engine.startPlaying()
        first.engine.stopPlaying()
        runCurrent()

        val second = QueueHarness(this, attached = false, store = FakeQueueStore(first.store.text))
        second.controller.attach(second.engine)
        second.controller.previous()
        second.engine.startPlaying()
        runCurrent()
        assertEquals(1, second.engine.currentIndex)
        assertEquals(0L, second.engine.positionMs)
        assertEquals(listOf("b" to "play-1"), second.source.reports)
    }

    @Test
    fun `a restored wave starts a new session when it needs more, and appends it`() = runTest {
        val first = QueueHarness(this)
        first.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3", "w4")) }
        first.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()

        val second = QueueHarness(this, store = FakeQueueStore(first.store.text))
        assertTrue(second.controller.state.value.isWave)
        second.controller.attach(second.engine)
        second.source.onStartWave = { WaveBatch("S2", "C1", tracks("n1", "n2")) }
        second.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        repeat(3) {
            second.engine.finishTrack()
            runCurrent()
        }
        assertEquals(
            "startWave [user:onyourwave]",
            second.source.calls.first {
                it.startsWith("startWave") ||
                    it.startsWith("moreWave")
            },
        )
        assertEquals(listOf("w1", "w2", "w3", "w4", "n1", "n2"), second.engine.ids())
        assertTrue(second.source.feedback.contains("radioStarted S2/user:onyourwave/C1 - 0.0"))
        assertFalse(second.source.calls.any { it.startsWith("moreWave S1") })
    }

    @Test
    fun `the feedback of a restored wave track goes to its saved session and batch`() = runTest {
        val first = QueueHarness(this)
        first.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
        first.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()

        val second = QueueHarness(this, attached = false, store = FakeQueueStore(first.store.text))
        val engine = FakeEngine(second.controller)
        second.controller.attach(engine)
        engine.startPlaying()
        runCurrent()
        assertEquals("trackStarted S1/user:onyourwave/B1 w1 0.0", second.source.feedback.last())
    }
}
