package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.WaveContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueFeedbackTest {
    private val first = WaveContext("S1", "user:onyourwave", "B1")

    @Test
    fun `TRK-03 TRK-04 radioStarted comes first, then trackStarted with the track's batch`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(
            listOf(
                "radioStarted ${ctx(first)} - 0.0",
                "trackStarted ${ctx(first)} w1 0.0",
            ),
            harness.source.feedback,
        )
    }

    @Test
    fun `TRK-04 a track from a later batch reports that batch`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.source.onMoreWave = { _, _ -> WaveBatch("", "B2", tracks("m1", "m2", "m3")) }
        harness.engine.startPlaying()
        runCurrent()
        repeat(3) {
            harness.engine.finishTrack()
            runCurrent()
        }
        assertEquals("trackStarted S1/user:onyourwave/B2 m1 0.0", harness.source.feedback.last())
    }

    @Test
    fun `TRK-05 TRK-08 a track played to its end is finished with the seconds that played`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.startPlaying()
        advanceTimeBy(10_000)
        harness.engine.stopPlaying()
        advanceTimeBy(5_000)
        harness.engine.startPlaying()
        advanceTimeBy(20_049)
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(
            listOf("trackFinished ${ctx(first)} w1 30.0", "trackStarted ${ctx(first)} w2 0.0"),
            harness.source.feedback.takeLast(2),
        )
    }

    @Test
    fun `TRK-06 Next closes the track as a skip before the next one starts`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.startPlaying()
        advanceTimeBy(4_000)
        harness.controller.next()
        runCurrent()
        assertEquals(
            listOf("skip ${ctx(first)} w1 4.0", "trackStarted ${ctx(first)} w2 0.0"),
            harness.source.feedback.takeLast(2),
        )
    }

    @Test
    fun `TRK-06 a restart, Stop, a new queue and removing the current track are skips`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.startPlaying()
        advanceTimeBy(5_000)
        harness.engine.positionMs = 5_000
        harness.controller.previous()
        runCurrent()
        assertEquals(
            listOf("skip ${ctx(first)} w1 5.0", "trackStarted ${ctx(first)} w1 0.0"),
            harness.source.feedback.takeLast(2),
        )

        advanceTimeBy(1_000)
        harness.controller.stop()
        runCurrent()
        assertEquals("skip ${ctx(first)} w1 1.0", harness.source.feedback.last())

        harness.engine.startPlaying()
        advanceTimeBy(2_000)
        harness.controller.removeIndices(setOf(0))
        runCurrent()
        assertEquals("skip ${ctx(first)} w1 2.0", harness.source.feedback.last { it.startsWith("skip") })

        harness.engine.startPlaying()
        harness.controller.loadSource("Liked") { tracks("l1") }
        runCurrent()
        assertEquals("skip ${ctx(first)} w2 0.0", harness.source.feedback.last())
    }

    @Test
    fun `TRK-07 a dislike in a wave acts as Next, so the track closes with a skip`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.startPlaying()
        advanceTimeBy(3_000)
        harness.controller.dislikeAndSkip(harness.engine.tracks[0])
        runCurrent()
        assertEquals(
            listOf("skip ${ctx(first)} w1 3.0", "trackStarted ${ctx(first)} w2 0.0"),
            harness.source.feedback.takeLast(2),
        )
        assertEquals(1, harness.engine.currentIndex)
    }

    @Test
    fun `TRK-07 a dislike of the current track from the notification closes it with a skip`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.seekTo(1)
        harness.engine.startPlaying()
        advanceTimeBy(2_000)
        harness.controller.dislikeCurrent()
        runCurrent()
        assertTrue(harness.source.calls.contains("dislike w2"))
        assertEquals(
            listOf("skip ${ctx(first)} w2 2.0", "trackStarted ${ctx(first)} w3 0.0"),
            harness.source.feedback.takeLast(2),
        )
        assertEquals(2, harness.engine.currentIndex)
    }

    @Test
    fun `the events of a track go to its own session after another wave replaced it`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.startPlaying()
        advanceTimeBy(1_000)
        harness.source.onStartWave = { WaveBatch("S2", "C1", tracks("r1", "r2", "r3")) }
        harness.controller.playWave(listOf("genre:rock"), "Рок")
        runCurrent()
        val second = WaveContext("S2", "genre:rock", "C1")
        assertEquals(
            listOf(
                "radioStarted ${ctx(second)} - 0.0",
                "skip ${ctx(first)} w1 1.0",
                "trackStarted ${ctx(second)} r1 0.0",
            ),
            harness.source.feedback.takeLast(3),
        )
    }

    @Test
    fun `an ordinary queue sends no feedback`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        harness.controller.next()
        runCurrent()
        assertEquals(emptyList<String>(), harness.source.feedback)
    }

    @Test
    fun `WAVE-03 an empty wave sends no feedback`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onStartWave = { WaveBatch("S1", "B1", emptyList()) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        assertEquals(emptyList<String>(), harness.source.feedback)
    }

    private fun wave(scope: TestScope, vararg ids: String): QueueHarness {
        val harness = QueueHarness(scope)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks(*ids)) }
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        scope.runCurrent()
        return harness
    }

    private fun ctx(context: WaveContext) = "${context.sessionId}/${context.stationId}/${context.batchId}"
}
