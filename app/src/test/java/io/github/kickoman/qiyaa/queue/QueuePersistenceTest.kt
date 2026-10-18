package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeEngine
import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.WaveContext
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
    fun `SHUF-01 with shuffle on, a restored queue starts a new pass from the restored track`() = runTest {
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

    @Test
    fun `CONT-01 CONT-05 the flag, the parts and the history survive a restart`() = runTest {
        val first = QueueHarness(this, continueWithVibe = true)
        first.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1")) }
        first.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        first.engine.startPlaying()
        runCurrent()
        first.controller.setContinueWithVibe(false)
        runCurrent()

        val second =
            QueueHarness(
                this,
                attached = false,
                store = FakeQueueStore(first.store.text),
                continueWithVibe = true,
            )
        val state = second.controller.state.value
        assertFalse(state.continueWithVibe)
        assertEquals(
            listOf(EntryPart.HEAD, EntryPart.HEAD, EntryPart.CONTINUATION),
            state.entries.map { it.part },
        )
        assertEquals(WaveContext("V1", "track:a", "B1"), state.entries[2].wave)
        assertEquals(listOf("a"), first.store.snapshot()!!.history)
    }

    @Test
    fun `CONT-01 a file saved before the flag existed takes it from the setting`() = runTest {
        val old =
            QueueSnapshot(
                tracks = tracks("a", "b"),
                batchIds = listOf("", ""),
                title = "Liked",
                sourceId = null,
                isWave = false,
                waveSessionId = "",
                waveStationId = "",
                index = 0,
                positionMs = 0,
                shuffle = false,
                repeat = false,
            )
        for (setting in listOf(true, false)) {
            val restored =
                QueueHarness(
                    this,
                    attached = false,
                    store = FakeQueueStore(QueueSnapshotCodec.encode(old)),
                    continueWithVibe = setting,
                )
            assertEquals(setting, restored.controller.state.value.continueWithVibe)
        }
    }

    @Test
    fun `CONT-04 a restored vibe from history starts a new session on the history's seeds`() = runTest {
        val first = QueueHarness(this, continueWithVibe = true)
        first.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2", "v3")) }
        first.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        first.engine.startPlaying()
        runCurrent()
        first.engine.finishTrack()
        first.engine.stopPlaying()
        runCurrent()

        val second = QueueHarness(this, store = FakeQueueStore(first.store.text), continueWithVibe = true)
        second.source.onStartWave = { WaveBatch("V2", "C1", tracks("n1")) }
        second.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        runCurrent()
        assertEquals(emptyList<String>(), second.source.calls)
        second.engine.finishTrack()
        second.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("startWave [track:b, track:a]"), second.source.calls)
        assertEquals(listOf("a", "b", "v1", "v2", "v3", "n1"), second.engine.ids())
        assertEquals(listOf("radioStarted V2/track:b/C1 - 0.0"), second.source.feedback)
        second.engine.startPlaying()
        second.engine.seekTo(5)
        runCurrent()
        assertEquals("trackStarted V2/track:b/C1 n1 0.0", second.source.feedback.last())
    }

    @Test
    fun `CONT-08 the feedback of a restored vibe track goes to its saved session and batch`() = runTest {
        val first = QueueHarness(this, continueWithVibe = true)
        first.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2", "v3")) }
        first.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        first.engine.startPlaying()
        runCurrent()
        first.engine.finishTrack()
        first.engine.finishTrack()
        first.engine.stopPlaying()
        runCurrent()

        val second = QueueHarness(this, attached = false, store = FakeQueueStore(first.store.text))
        val engine = FakeEngine(second.controller)
        second.controller.attach(engine)
        engine.startPlaying()
        runCurrent()
        assertEquals(listOf("trackStarted V1/track:a/B1 v1 0.0"), second.source.feedback)
    }

    @Test
    fun `CONT-09 with shuffle on, a restored head with vibe tracks plays a head pass, then the vibe`() =
        runTest {
            val first = QueueHarness(this, continueWithVibe = true)
            first.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            first.controller.loadSource("Liked") { tracks("a", "b", "c", "d") }
            runCurrent()
            first.controller.setShuffle(true)
            first.engine.startPlaying()
            first.engine.finishTrack()
            first.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("a", "b", "c", "d", "v1", "v2"), first.engine.ids())
            first.engine.seekTo(1)
            first.engine.stopPlaying()
            runCurrent()

            val second = QueueHarness(this, store = FakeQueueStore(first.store.text), continueWithVibe = true)
            runCurrent()
            assertTrue(second.engine.shuffleEnabled)
            assertEquals(listOf(1, 0, 2, 3, 4, 5), second.engine.playOrder())
            assertEquals(emptyList<String>(), second.source.calls)
        }
}
