package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeContinuation
import io.github.kickoman.qiyaa.support.FakeEngine
import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackKey
import io.github.kickoman.qiyaa.track.TrackSource
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
    fun `CONT-01 CONT-05 the file of a Yandex queue with a vibe from history is written as before #113`() =
        runTest {
            val harness = QueueHarness(this, continueWithVibe = true)
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            harness.controller.loadSource("Liked", sourceId = "liked") { tracks("a", "b") }
            runCurrent()
            harness.engine.startPlaying()
            runCurrent()
            harness.engine.finishTrack()
            harness.engine.positionMs = 7_000
            harness.engine.stopPlaying()
            runCurrent()
            assertEquals(YANDEX_VIBE_FILE, harness.store.text)
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

    @Test
    fun `SHUF-02 with shuffle on, Previous on a restored first vibe track goes to the row before`() =
        runTest {
            val first = QueueHarness(this, continueWithVibe = true)
            first.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            first.controller.loadSource("Liked") { tracks("a", "b", "c") }
            runCurrent()
            first.controller.setShuffle(true)
            first.engine.startPlaying()
            first.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("a", "b", "c", "v1", "v2"), first.engine.ids())
            first.engine.seekTo(3)
            first.engine.stopPlaying()
            runCurrent()

            val second = QueueHarness(this, store = FakeQueueStore(first.store.text), continueWithVibe = true)
            second.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            second.engine.shuffleOrder = listOf(2, 0, 1)
            assertEquals(listOf(2, 0, 1, 3, 4), second.engine.playOrder())
            second.controller.previous()
            assertEquals(2, second.engine.currentIndex)
        }

    @Test
    fun `SPOT-20 a Spotify queue keeps its autoplay key and started tracks, and asks anew after a restart`() =
        runTest {
            val key = ContinuationKey("spotify-autoplay", "spotify:playlist:p1")
            val autoplay = FakeContinuation(key)
            autoplay.replies(listOf(spotify("x1"), spotify("x2")))
            val first = QueueHarness(this, continueWithVibe = true)
            first.controller.loadSource("Road", sourceId = "spotify:playlist:p1", continuation = autoplay) {
                listOf(spotify("s1"), spotify("s2"), spotify("s3"))
            }
            runCurrent()
            first.engine.startPlaying()
            first.engine.finishTrack()
            runCurrent()
            first.engine.stopPlaying()
            runCurrent()
            val saved = first.store.snapshot()!!
            assertEquals(key, saved.continuation)
            assertEquals(listOf("s1", "s2").map { TrackKey(TrackSource.SPOTIFY, it) }, saved.started)

            val keys = ArrayList<ContinuationKey>()
            val restored = FakeContinuation(key)
            restored.replies(listOf(spotify("n1")))
            val second =
                QueueHarness(
                    this,
                    store = FakeQueueStore(first.store.text),
                    continueWithVibe = true,
                    continuationOf = { asked ->
                        keys += asked
                        restored
                    },
                )
            assertEquals(listOf(key), keys)
            assertEquals(listOf("s1", "s2", "s3", "x1", "x2"), second.engine.ids())
            second.engine.startPlaying()
            second.engine.finishTrack()
            second.engine.finishTrack()
            runCurrent()
            assertEquals(listOf(listOf("s1", "s2", "s3")), restored.asked)
            assertEquals(listOf("s1", "s2", "s3", "x1", "x2", "n1"), second.engine.ids())
        }

    @Test
    fun `SPOT-12 a restored radio is a station again, going on from its key, with the modes locked`() =
        runTest {
            val key = ContinuationKey("spotify-station", "spotify:track:r")
            val radio = FakeContinuation(key)
            radio.replies(listOf(spotify("r1"), spotify("r2"), spotify("r3")))
            val first = QueueHarness(this)
            first.controller.playContinuation("Song: radio", "spotify:radio:r", radio)
            runCurrent()

            val restored = FakeContinuation(key)
            restored.replies(listOf(spotify("r4")))
            val second =
                QueueHarness(this, store = FakeQueueStore(first.store.text), continuationOf = { restored })
            val state = second.controller.state.value
            assertTrue(state.isWave)
            assertEquals("spotify:radio:r", state.activeSourceId)
            assertTrue(second.controller.modes.value.locked)
            second.engine.finishTrack()
            runCurrent()
            assertEquals(listOf(emptyList<String>()), restored.asked)
            assertEquals(listOf("r1", "r2", "r3", "r4"), second.engine.ids())
        }

    @Test
    fun `a continuation key this build cannot make leaves a Spotify head without one`() = runTest {
        val autoplay = FakeContinuation(ContinuationKey("spotify-future", "spotify:playlist:p1"))
        val first = QueueHarness(this, continueWithVibe = true)
        first.controller.loadSource("Road", continuation = autoplay) {
            listOf(spotify("s1"), spotify("s2"), spotify("s3"))
        }
        runCurrent()

        val second = QueueHarness(this, store = FakeQueueStore(first.store.text), continueWithVibe = true)
        second.engine.startPlaying()
        repeat(3) { second.engine.finishTrack() }
        runCurrent()
        assertTrue(second.engine.isEnded)
        assertEquals(emptyList<String>(), second.source.calls)
        assertEquals(null, second.store.snapshot()!!.continuation)
    }

    private fun spotify(id: String) = Track(id = id, title = "Spotify $id", source = TrackSource.SPOTIFY)

    private companion object {
        const val YANDEX_VIBE_FILE =
            """{"version":1,"title":"Liked","sourceId":"liked","isWave":false,"waveSessionId":"V1",""" +
                """"waveStationId":"track:a","index":1,"positionMs":7000,"shuffle":false,"repeat":false,""" +
                """"continueWithVibe":true,"history":["b","a"],"tracks":[""" +
                """{"id":"a","title":"Ta"},{"id":"b","title":"Tb"},""" +
                """{"id":"v1","title":"Tv1","batchId":"B1","part":"continuation"},""" +
                """{"id":"v2","title":"Tv2","batchId":"B1","part":"continuation"}]}"""
    }
}
