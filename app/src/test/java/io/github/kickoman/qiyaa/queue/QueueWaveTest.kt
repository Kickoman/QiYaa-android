package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.MalformedResponseException
import io.github.kickoman.qiyaa.yandex.NetworkException
import io.github.kickoman.qiyaa.yandex.WaveBatch
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueWaveTest {
    @Test
    fun `WAVE-01 a wave says loading at once, then replaces the queue and plays`() = runTest {
        val harness = QueueHarness(this)
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        assertEquals(listOf(QueueEvent.SourceLoading("My Wave")), harness.events)
        assertEquals(emptyList<String>(), harness.engine.ids())
        reply.complete(WaveBatch("S1", "B1", tracks("w1", "w2", "w3", "w4")))
        runCurrent()
        assertEquals(
            "set [w1, w2, w3, w4] play=true",
            harness.engine.commands.last {
                it.startsWith("set")
            },
        )
        assertTrue(harness.controller.state.value.isWave)
        assertEquals("user:onyourwave", harness.controller.state.value.activeSourceId)
        assertEquals(QueueEvent.WaveStarted("My Wave"), harness.events.last())
    }

    @Test
    fun `WAVE-02 My Wave's seed is user onyourwave and a station's seed is its id`() = runTest {
        val harness = QueueHarness(this)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3", "w4")) }
        assertEquals("user:onyourwave", QueueController.MY_WAVE_SEED)
        harness.controller.playWave(listOf("genre:rock"), "Рок")
        runCurrent()
        assertEquals("startWave [genre:rock]", harness.source.calls.single())
        assertEquals("genre:rock", harness.controller.state.value.activeSourceId)
    }

    @Test
    fun `WAVE-03 an empty first batch leaves the queue and is reported empty`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Old") { tracks("o1", "o2", "o3") }
        runCurrent()
        harness.engine.commands.clear()
        for (batch in listOf(emptyList(), listOf(track("x", available = false)))) {
            harness.source.onStartWave = { WaveBatch("S1", "B1", batch) }
            harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
            runCurrent()
            assertEquals(listOf("o1", "o2", "o3"), harness.engine.ids())
            assertEquals(emptyList<String>(), harness.engine.commands)
            assertEquals(false, harness.controller.state.value.isWave)
            assertEquals(QueueEvent.SourceEmpty("My Wave"), harness.events.last())
        }
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(
            "no load-more for a wave that never started",
            emptyList<String>(),
            harness.source.calls.filter {
                it.startsWith("moreWave")
            },
        )
    }

    @Test
    fun `WAVE-04 a failed start leaves the queue and shows an error`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Old") { tracks("o1") }
        runCurrent()
        harness.source.onStartWave =
            { throw MalformedResponseException("POST", "/rotor/session/new", "no radioSessionId") }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        assertEquals(listOf("o1"), harness.engine.ids())
        assertEquals(Stage.WAVE, (harness.events.last() as QueueEvent.Failed).stage)
    }

    @Test
    fun `WAVE-05 two tracks from the end it asks for more with the last five ids and appends them`() =
        runTest {
            val harness = startedWave(this, "w1", "w2", "w3", "w4", "w5", "w6")
            harness.source.onMoreWave =
                { _, _ -> WaveBatch("", "B2", listOf(track("m1"), track("x", available = false))) }
            repeat(3) { harness.engine.finishTrack() }
            runCurrent()
            assertEquals(emptyList<String>(), harness.source.calls.filter { it.startsWith("moreWave") })
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(
                listOf("moreWave S1 [w2, w3, w4, w5, w6]"),
                harness.source.calls.filter {
                    it.startsWith("moreWave")
                },
            )
            assertEquals(listOf("w1", "w2", "w3", "w4", "w5", "w6", "m1"), harness.engine.ids())
            assertFalse(harness.controller.state.value.loadingMore)
        }

    @Test
    fun `WAVE-06 at most one load-more is in flight`() = runTest {
        val harness = startedWave(this, "w1", "w2", "w3")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onMoreWave = { _, _ -> reply.await() }
        harness.engine.finishTrack()
        runCurrent()
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(1, harness.source.calls.count { it.startsWith("moreWave") })
        assertTrue(harness.controller.state.value.loadingMore)
    }

    @Test
    fun `WAVE-07 a reply for a replaced queue is not appended`() = runTest {
        val harness = startedWave(this, "w1", "w2")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onMoreWave = { _, _ -> reply.await() }
        harness.engine.finishTrack()
        runCurrent()
        harness.controller.loadSource("Liked") { tracks("l1") }
        runCurrent()
        reply.complete(WaveBatch("S1", "B2", tracks("m1")))
        runCurrent()
        assertEquals(listOf("l1"), harness.engine.ids())
        assertEquals(QueueEvent.SourceLoaded("Liked", 1), harness.events.last())
    }

    // Gap A4 (Kickoman/QiYaa-android#29): the spec drops a stale failure silently.
    @Test
    fun `WAVE-07 gap A4 - a failed reply for a replaced queue currently still shows an error`() = runTest {
        val harness = startedWave(this, "w1", "w2")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onMoreWave = { _, _ -> reply.await() }
        harness.engine.finishTrack()
        runCurrent()
        harness.controller.loadSource("Liked") { tracks("l1") }
        runCurrent()
        reply.completeExceptionally(
            NetworkException("POST", "/rotor/session/S1/tracks", IOException("offline")),
        )
        runCurrent()
        assertEquals(listOf("l1"), harness.engine.ids())
        assertEquals(Stage.WAVE_MORE, (harness.events.last() as QueueEvent.Failed).stage)
    }

    @Test
    fun `WAVE-08 when the wave ends during a load-more it continues with the first new track`() = runTest {
        val harness = startedWave(this, "w1", "w2")
        val reply = CompletableDeferred<WaveBatch>()
        var requests = 0
        harness.source.onMoreWave =
            { _, _ -> if (++requests == 1) reply.await() else CompletableDeferred<WaveBatch>().await() }
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        assertTrue(harness.engine.isEnded)
        reply.complete(WaveBatch("S1", "B2", tracks("m1", "m2")))
        runCurrent()
        assertEquals(2, harness.engine.currentIndex)
        assertEquals(
            listOf("append [m1, m2]", "seek 2", "prepare", "play"),
            harness.engine.commands.takeLast(4),
        )
        assertEquals(4, harness.engine.itemCount)
    }

    @Test
    fun `WAVE-10 a wave plays in queue order even if shuffle was on`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("l1", "l2") }
        runCurrent()
        harness.engine.shuffleEnabled = true
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        assertFalse(harness.engine.shuffleEnabled)
        harness.engine.shuffleEnabled = true
        assertFalse("turning shuffle on during a wave is reverted", harness.engine.shuffleEnabled)
    }

    @Test
    fun `WAVE-11 an ordinary queue after a wave gets the user's shuffle back`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked") { tracks("l1", "l2") }
        runCurrent()
        harness.engine.shuffleEnabled = true
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        harness.controller.loadSource("Liked") { tracks("l1", "l2") }
        runCurrent()
        assertTrue(harness.engine.shuffleEnabled)
    }

    // Gap A2 (Kickoman/QiYaa-android#27): /play-audio goes out when the item changes, even
    // without autoplay, and is not repeated for the same track.
    @Test
    fun `TRK-01 TRK-02 gap A2 - play-audio is currently sent on item change once per track id`() = runTest {
        val harness = QueueHarness(this)
        harness.controller.loadSource("Liked", autoplay = false) { tracks("a", "b") }
        runCurrent()
        assertEquals(listOf("a" to "play-1"), harness.source.reports)
        harness.engine.seekTo(0)
        runCurrent()
        assertEquals(1, harness.source.reports.size)
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("a" to "play-1", "b" to "play-2"), harness.source.reports)
    }

    private fun startedWave(scope: TestScope, vararg ids: String): QueueHarness {
        val harness = QueueHarness(scope)
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks(*ids)) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        scope.runCurrent()
        harness.events.clear()
        harness.source.calls.clear()
        return harness
    }
}
