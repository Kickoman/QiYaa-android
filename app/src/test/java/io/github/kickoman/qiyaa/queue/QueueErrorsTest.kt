package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueErrorsTest {
    @Test
    fun `ERR-01 a network failure pauses on the track and says it waits for the network`() = runTest {
        val harness = loaded(this, "a", "b", "c")
        harness.network.value = false
        harness.engine.fail(FailureKind.NETWORK)
        harness.engine.fail(FailureKind.NETWORK)
        runCurrent()
        assertEquals(0, harness.engine.currentIndex)
        assertEquals(listOf(QueueEvent.WaitingForNetwork), harness.events)
        assertEquals(0, harness.engine.commands.count { it == "prepare" || it == "next" })
    }

    @Test
    fun `ERR-02 the track loads again when the network comes back`() = runTest {
        val harness = loaded(this, "a", "b")
        harness.network.value = false
        harness.engine.fail(FailureKind.NETWORK)
        advanceTimeBy(5 * 60_000L)
        assertEquals(0, harness.engine.commands.count { it == "prepare" })
        harness.network.value = true
        runCurrent()
        assertEquals(listOf("prepare"), harness.engine.commands)
        assertEquals(0, harness.engine.currentIndex)
    }

    @Test
    fun `ERR-02 with the network up all along it retries after 2, 4 and 8 seconds`() = runTest {
        val harness = loaded(this, "a", "b")
        val retries = ArrayList<Long>()
        repeat(3) {
            harness.engine.fail(FailureKind.NETWORK)
            val start = testScheduler.currentTime
            while (harness.engine.commands.count { it == "prepare" } == retries.size) advanceTimeBy(500)
            retries += (testScheduler.currentTime - start) / 1000
        }
        assertEquals(listOf(2L, 4L, 8L), retries)
        assertEquals(listOf(QueueEvent.WaitingForNetwork), harness.events)
    }

    @Test
    fun `ERR-04 ERR-05 a broken track is skipped twice in a row and the third stops`() = runTest {
        val harness = loaded(this, "a", "b", "c", "d")
        harness.engine.fail(FailureKind.TRACK)
        assertEquals(1, harness.engine.currentIndex)
        harness.engine.fail(FailureKind.TRACK)
        assertEquals(2, harness.engine.currentIndex)
        harness.engine.fail(FailureKind.TRACK)
        assertEquals(2, harness.engine.currentIndex)
        runCurrent()
        assertEquals(
            listOf(
                QueueEvent.Failed(Stage.PLAYBACK, "boom"),
                QueueEvent.Failed(Stage.PLAYBACK, "boom"),
                QueueEvent.StoppedAfterFailures(3),
            ),
            harness.events,
        )
    }

    @Test
    fun `ERR-06 the failure count restarts when a track plays or a new queue is set`() = runTest {
        val harness = loaded(this, "a", "b", "c", "d", "e")
        harness.engine.fail(FailureKind.TRACK)
        harness.engine.fail(FailureKind.TRACK)
        harness.engine.startPlaying()
        harness.engine.fail(FailureKind.TRACK)
        harness.engine.fail(FailureKind.TRACK)
        assertEquals(4, harness.engine.currentIndex)
        harness.controller.loadSource("New") { tracks("n1", "n2", "n3") }
        runCurrent()
        harness.engine.fail(FailureKind.TRACK)
        harness.engine.fail(FailureKind.TRACK)
        assertEquals(2, harness.engine.currentIndex)
    }

    @Test
    fun `ERR-07 a broken last track of a finite queue stops`() = runTest {
        val harness = loaded(this, "a")
        harness.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertEquals(QueueEvent.StoppedAfterFailures(1), harness.events.last())
    }

    // Gap A4 (Kickoman/QiYaa-android#29): in a wave the spec waits for more instead of stopping.
    @Test
    fun `ERR-07 gap A4 - a broken last track of a wave currently stops`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1")) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        harness.events.clear()
        harness.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertEquals(QueueEvent.StoppedAfterFailures(1), harness.events.last())
    }

    @Test
    fun `a rejected token holds the queue in place`() = runTest {
        val harness = loaded(this, "a", "b")
        harness.engine.fail(FailureKind.SESSION)
        runCurrent()
        assertEquals(0, harness.engine.currentIndex)
        assertEquals(listOf(QueueEvent.Failed(Stage.PLAYBACK, "boom")), harness.events)
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
