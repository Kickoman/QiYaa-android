package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueTrackingTest {
    @Test
    fun `TRK-01 play-audio goes out when a track starts playing, not when it is only queued`() = runTest {
        val harness = loaded(this, autoplay = false, "a", "b")
        assertEquals(emptyList<Pair<String, String>>(), harness.source.reports)
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a" to "play-1"), harness.source.reports)
    }

    @Test
    fun `TRK-01 pausing and resuming or seeking inside a track is not a new start`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b")
        harness.engine.startPlaying()
        harness.engine.stopPlaying()
        harness.engine.startPlaying()
        harness.engine.seekToPosition(60_000)
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a" to "play-1"), harness.source.reports)
    }

    @Test
    fun `TRK-01 each track that starts gets its own report with a new play id`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b")
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a" to "play-1", "b" to "play-2"), harness.source.reports)
    }

    @Test
    fun `TRK-01 a track whose link failed has not started and is not reported`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b")
        harness.engine.fail(FailureKind.TRACK)
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("b" to "play-1"), harness.source.reports)
    }

    @Test
    fun `TRK-02 a restart with Previous after 3 s reports the same track again`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b")
        harness.engine.startPlaying()
        harness.engine.positionMs = 10_000
        harness.controller.previous()
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a" to "play-1", "a" to "play-2"), harness.source.reports)
    }

    @Test
    fun `TRK-02 repeat of a one-track queue reports the track again`() = runTest {
        val harness = loaded(this, autoplay = true, "a")
        harness.engine.repeatEnabled = true
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a" to "play-1", "a" to "play-2"), harness.source.reports)
    }

    @Test
    fun `TRK-02 playing again after Stop or after the end is a new start`() = runTest {
        val harness = loaded(this, autoplay = true, "a")
        harness.engine.startPlaying()
        harness.controller.stop()
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a" to "play-1", "a" to "play-2", "a" to "play-3"), harness.source.reports)
    }

    private fun loaded(scope: TestScope, autoplay: Boolean, vararg ids: String): QueueHarness {
        val harness = QueueHarness(scope)
        harness.controller.loadSource("Liked", autoplay = autoplay) { tracks(*ids) }
        scope.runCurrent()
        return harness
    }
}
