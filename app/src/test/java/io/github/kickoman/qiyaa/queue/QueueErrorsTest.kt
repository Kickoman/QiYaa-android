package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.NetworkLoss
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.wave
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueErrorsTest {
    private val w1PlayedOnceThenW2 =
        listOf(
            "radioStarted S1/user:onyourwave/B1 - 0.0",
            "trackStarted S1/user:onyourwave/B1 w1 0.0",
            "trackFinished S1/user:onyourwave/B1 w1 30.0",
            "trackStarted S1/user:onyourwave/B1 w2 0.0",
        )

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
                QueueEvent.Failed(Stage.PLAYBACK, ErrorKind.TrackUnplayable),
                QueueEvent.Failed(Stage.PLAYBACK, ErrorKind.TrackUnplayable),
                QueueEvent.StoppedAfterFailures(3),
            ),
            harness.events,
        )
    }

    @Test
    fun `ERR-04 a broken track of a restored paused queue is skipped and playback does not start`() =
        runTest {
            val first = QueueHarness(this)
            first.controller.loadSource("Liked") { tracks("a", "b", "c") }
            runCurrent()
            first.engine.pause()
            runCurrent()
            val restored = QueueHarness(this, store = FakeQueueStore(first.store.text))
            assertFalse(restored.engine.playWhenReady)
            restored.engine.fail(FailureKind.TRACK)
            assertEquals(1, restored.engine.currentIndex)
            assertFalse(restored.engine.playWhenReady)
            assertEquals(0, restored.engine.commands.count { it == "play" })
        }

    @Test
    fun `ERR-04 a broken track the user wanted playing is skipped and the next one plays`() = runTest {
        val harness = loaded(this, "a", "b")
        harness.engine.fail(FailureKind.TRACK)
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(listOf("play"), harness.engine.commands.filter { it == "play" })
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

    @Test
    fun `ERR-07 a broken last track of a wave waits for more and continues with the first new track`() =
        runTest {
            val harness = QueueHarness(this)
            val reply = CompletableDeferred<WaveBatch>()
            var requests = 0
            harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
            harness.source.onMoreWave =
                { _, _ -> if (++requests == 1) reply.await() else CompletableDeferred<WaveBatch>().await() }
            harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
            runCurrent()
            harness.engine.finishTrack()
            harness.engine.finishTrack()
            runCurrent()
            harness.events.clear()
            harness.engine.commands.clear()
            harness.engine.fail(FailureKind.TRACK)
            runCurrent()
            assertEquals(listOf(QueueEvent.Failed(Stage.PLAYBACK, ErrorKind.TrackUnplayable)), harness.events)
            assertEquals(emptyList<String>(), harness.engine.commands)
            reply.complete(WaveBatch("S1", "B2", tracks("m1")))
            runCurrent()
            assertEquals(listOf("append [m1]", "seek 3", "prepare", "play"), harness.engine.commands)
        }

    @Test
    fun `ERR-05 the third broken track in a row stops a wave too`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        repeat(3) { harness.engine.fail(FailureKind.TRACK) }
        runCurrent()
        assertEquals(QueueEvent.StoppedAfterFailures(3), harness.events.last())
    }

    @Test
    fun `ERR-09 a wave track that waits for the network and continues is the same play`() = runTest {
        for (loss in NetworkLoss.entries) {
            val harness = wave(this, "w1", "w2", "w3")
            harness.engine.startPlaying()
            advanceTimeBy(10_000)
            harness.engine.commands.clear()
            harness.network.value = false
            loss.hit(harness.engine)
            advanceTimeBy(60_000)
            harness.network.value = true
            runCurrent()
            assertEquals(loss.name, listOf("prepare"), harness.engine.commands)
            harness.engine.startPlaying()
            advanceTimeBy(20_000)
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(loss.name, w1PlayedOnceThenW2, harness.source.feedback)
            assertEquals(loss.name, listOf("w1" to "play-1", "w2" to "play-2"), harness.source.reports)
        }
    }

    @Test
    fun `ERR-09 a track of a finite queue that waits for the network is reported once`() = runTest {
        for (loss in NetworkLoss.entries) {
            val harness = loaded(this, "a", "b")
            harness.engine.startPlaying()
            advanceTimeBy(10_000)
            harness.network.value = false
            loss.hit(harness.engine)
            advanceTimeBy(60_000)
            harness.network.value = true
            runCurrent()
            assertEquals(loss.name, listOf("prepare"), harness.engine.commands)
            harness.engine.startPlaying()
            advanceTimeBy(20_000)
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(loss.name, listOf("a" to "play-1", "b" to "play-2"), harness.source.reports)
        }
    }

    @Test
    fun `ERR-09 a wave track that failed for the network before it started starts once when it plays`() =
        runTest {
            val harness = wave(this, "w1", "w2", "w3")
            harness.engine.commands.clear()
            harness.network.value = false
            harness.engine.fail(FailureKind.NETWORK)
            advanceTimeBy(60_000)
            harness.network.value = true
            runCurrent()
            assertEquals(listOf("prepare"), harness.engine.commands)
            assertEquals(listOf("radioStarted S1/user:onyourwave/B1 - 0.0"), harness.source.feedback)
            assertEquals(emptyList<Pair<String, String>>(), harness.source.reports)
            harness.engine.startPlaying()
            runCurrent()
            assertEquals(w1PlayedOnceThenW2.take(2), harness.source.feedback)
            assertEquals(listOf("w1" to "play-1"), harness.source.reports)
        }

    @Test
    fun `ERR-09 Next during the network wait of a track that never started sends no skip for it`() = runTest {
        val harness = wave(this, "w1", "w2", "w3")
        harness.engine.commands.clear()
        harness.network.value = false
        harness.engine.fail(FailureKind.NETWORK)
        advanceTimeBy(30_000)
        harness.controller.next()
        harness.network.value = true
        runCurrent()
        assertEquals(listOf("next", "prepare"), harness.engine.commands)
        assertEquals(1, harness.engine.currentIndex)
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(
            listOf(
                "radioStarted S1/user:onyourwave/B1 - 0.0",
                "trackStarted S1/user:onyourwave/B1 w2 0.0",
            ),
            harness.source.feedback,
        )
        assertEquals(listOf("w2" to "play-1"), harness.source.reports)
    }

    @Test
    fun `ERR-02 ERR-09 a pause by the user during the wait holds after the retry and stays one play`() =
        runTest {
            for (loss in NetworkLoss.entries) {
                val harness = wave(this, "w1", "w2", "w3")
                harness.engine.startPlaying()
                advanceTimeBy(10_000)
                harness.engine.commands.clear()
                harness.network.value = false
                loss.hit(harness.engine)
                harness.engine.pause()
                advanceTimeBy(60_000)
                harness.network.value = true
                runCurrent()
                assertEquals(loss.name, listOf("pause", "prepare"), harness.engine.commands)
                assertFalse(loss.name, harness.engine.playWhenReady)
                harness.engine.play()
                harness.engine.startPlaying()
                advanceTimeBy(20_000)
                harness.engine.finishTrack()
                runCurrent()
                assertEquals(loss.name, w1PlayedOnceThenW2, harness.source.feedback)
                assertEquals(loss.name, listOf("w1" to "play-1", "w2" to "play-2"), harness.source.reports)
            }
        }

    @Test
    fun `ERR-09 Play pressed before the retry fires is the same play`() = runTest {
        for (loss in NetworkLoss.entries) {
            val harness = wave(this, "w1", "w2", "w3")
            harness.engine.startPlaying()
            advanceTimeBy(10_000)
            harness.engine.commands.clear()
            harness.network.value = false
            loss.hit(harness.engine)
            advanceTimeBy(30_000)
            harness.engine.startPlaying()
            harness.network.value = true
            runCurrent()
            assertEquals(loss.name, 0, harness.engine.commands.count { it == "prepare" })
            advanceTimeBy(20_000)
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(loss.name, 0, harness.engine.commands.count { it == "prepare" })
            assertEquals(loss.name, w1PlayedOnceThenW2, harness.source.feedback)
            assertEquals(loss.name, listOf("w1" to "play-1", "w2" to "play-2"), harness.source.reports)
        }
    }

    @Test
    fun `ERR-09 several failed retries before the track plays again are still one play`() = runTest {
        for (loss in NetworkLoss.entries) {
            val harness = wave(this, "w1", "w2", "w3")
            harness.engine.startPlaying()
            advanceTimeBy(10_000)
            harness.engine.commands.clear()
            loss.hit(harness.engine)
            advanceTimeBy(2_000)
            runCurrent()
            harness.engine.fail(FailureKind.NETWORK)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(loss.name, listOf("prepare", "prepare"), harness.engine.commands)
            harness.engine.startPlaying()
            advanceTimeBy(20_000)
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(loss.name, w1PlayedOnceThenW2, harness.source.feedback)
            assertEquals(loss.name, listOf("w1" to "play-1", "w2" to "play-2"), harness.source.reports)
        }
    }

    @Test
    fun `a rejected token holds the queue in place`() = runTest {
        val harness = loaded(this, "a", "b")
        harness.engine.fail(FailureKind.SESSION, ErrorKind.TokenRejected)
        runCurrent()
        assertEquals(0, harness.engine.currentIndex)
        assertEquals(listOf(QueueEvent.Failed(Stage.PLAYBACK, ErrorKind.TokenRejected)), harness.events)
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
