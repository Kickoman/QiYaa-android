package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueActivityTest {
    @Test
    fun `TRK-01 TrackStarted comes when a track really starts, not when it is queued, paused or sought`() =
        runTest {
            val harness = loaded(this, autoplay = false, "a", "b")
            val seen = activity(harness)
            runCurrent()
            assertEquals(0, seen.count { it == QueueActivity.TrackStarted })

            harness.engine.startPlaying()
            harness.engine.stopPlaying()
            harness.engine.startPlaying()
            harness.engine.seekToPosition(60_000)
            harness.engine.startPlaying()
            runCurrent()
            assertEquals(1, seen.count { it == QueueActivity.TrackStarted })
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(2, seen.count { it == QueueActivity.TrackStarted })
        }

    @Test
    fun `TRK-01 a jam track that starts is a TrackStarted too`() = runTest {
        val harness = QueueHarness(this)
        val seen = activity(harness)
        harness.controller.startJam(Silent, waveFeedback = false)
        harness.controller.onJamQueue(listOf(JamEntry("i1", track("A"), "g1")), emptyList(), 0)

        harness.engine.startPlaying()
        runCurrent()

        assertEquals(1, seen.count { it == QueueActivity.TrackStarted })
    }

    @Test
    fun `PlayingChanged follows the engine`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b")
        val seen = activity(harness)

        harness.engine.startPlaying()
        harness.engine.stopPlaying()
        runCurrent()

        assertEquals(
            listOf(QueueActivity.PlayingChanged(true), QueueActivity.PlayingChanged(false)),
            seen.filterIsInstance<QueueActivity.PlayingChanged>(),
        )
    }

    @Test
    fun `ERR-01 ERR-09 a network wait is one failure however many retries fail, a later wait another`() =
        runTest {
            val harness = loaded(this, autoplay = true, "a", "b")
            val seen = activity(harness)
            repeat(3) {
                harness.engine.fail(FailureKind.NETWORK)
                advanceTimeBy(60_000)
                runCurrent()
            }
            assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.NETWORK)), failures(seen))

            harness.engine.startPlaying()
            harness.engine.fail(FailureKind.NETWORK)
            runCurrent()

            assertEquals(2, failures(seen).size)
        }

    @Test
    fun `ERR-01 offline the failures during the wait are not counted again`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b")
        val seen = activity(harness)
        harness.network.value = false

        repeat(3) { harness.engine.fail(FailureKind.NETWORK) }
        runCurrent()

        assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.NETWORK)), failures(seen))
    }

    @Test
    fun `a rejected token that holds the queue is one failure`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b")
        val seen = activity(harness)

        harness.engine.fail(FailureKind.SESSION, ErrorKind.TokenRejected)
        runCurrent()

        assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.SESSION)), failures(seen))
    }

    @Test
    fun `ERR-04 ERR-05 each broken track counts, the skipped ones and the one that stops`() = runTest {
        val harness = loaded(this, autoplay = true, "a", "b", "c", "d")
        val seen = activity(harness)

        repeat(3) { harness.engine.fail(FailureKind.TRACK) }
        runCurrent()

        assertEquals(List(3) { QueueActivity.PlaybackFailed(FailureKind.TRACK) }, failures(seen))
    }

    @Test
    fun `ERR-07 a broken last track counts whether the queue stops or waits for more`() = runTest {
        val finite = loaded(this, autoplay = true, "a")
        val stopped = activity(finite)
        finite.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.TRACK)), failures(stopped))

        val wave = QueueHarness(this)
        wave.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
        wave.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        wave.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        wave.engine.finishTrack()
        wave.engine.finishTrack()
        val waiting = activity(wave)
        wave.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.TRACK)), failures(waiting))
    }

    @Test
    fun `HOST-36 ERR-10 a held item that breaks on pause is one failure, the next item waits on pause`() =
        runTest {
            val harness = held(this)
            val seen = activity(harness)

            harness.engine.fail(FailureKind.TRACK)
            runCurrent()

            assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.TRACK)), failures(seen))
            assertEquals("Y", harness.engine.tracks[harness.engine.currentIndex].id)
            assertEquals("prepare", harness.engine.commands.last())
            assertFalse(harness.engine.playWhenReady)
        }

    @Test
    fun `HOST-36 ERR-09 a held item that waits for the network is one failure, however many retries fail`() =
        runTest {
            val harness = held(this)
            val seen = activity(harness)

            repeat(3) {
                harness.engine.fail(FailureKind.NETWORK)
                advanceTimeBy(60_000)
                runCurrent()
            }

            assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.NETWORK)), failures(seen))
        }

    @Test
    fun `HOST-09 ERR-07 a broken last jam item that waits for the jam wave is one failure`() = runTest {
        val harness = QueueHarness(this, continueWithVibe = true)
        harness.controller.startJam(Silent, waveFeedback = false)
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.controller.onJamQueue(listOf(JamEntry("i1", track("A"), "g1")), listOf("track:A"), 1)
        runCurrent()
        val seen = activity(harness)

        harness.engine.fail(FailureKind.TRACK)
        runCurrent()
        reply.complete(WaveBatch("SJ", "B1", tracks("w1")))
        runCurrent()

        assertEquals(listOf(QueueActivity.PlaybackFailed(FailureKind.TRACK)), failures(seen))
        assertEquals("w1", harness.engine.tracks[harness.engine.currentIndex].id)
    }

    private fun held(scope: TestScope): QueueHarness {
        val harness = QueueHarness(scope)
        harness.controller.startJam(Silent, waveFeedback = false)
        harness.controller.loadSource("Mix") { tracks("m1", "m2") }
        scope.runCurrent()
        harness.controller.onJamQueue(
            listOf(JamEntry("i1", track("X"), "g1"), JamEntry("i2", track("Y"), "g1")),
            emptyList(),
            0,
        )
        assertEquals("X", harness.engine.tracks[harness.engine.currentIndex].id)
        assertFalse(harness.engine.playWhenReady)
        return harness
    }

    private fun TestScope.activity(harness: QueueHarness): List<QueueActivity> {
        val seen = ArrayList<QueueActivity>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            harness.controller.activity.collect { seen += it }
        }
        return seen
    }

    private fun failures(seen: List<QueueActivity>) = seen.filterIsInstance<QueueActivity.PlaybackFailed>()

    private fun loaded(scope: TestScope, autoplay: Boolean, vararg ids: String): QueueHarness {
        val harness = QueueHarness(scope)
        harness.controller.loadSource("Liked", autoplay = autoplay) { tracks(*ids) }
        scope.runCurrent()
        return harness
    }

    private object Silent : JamPlaybackListener {
        override fun onItemStarted(itemId: String) = Unit

        override fun onPlayback(playback: JamPlayback) = Unit

        override fun onRemoveItems(itemIds: List<String>) = Unit
    }
}
