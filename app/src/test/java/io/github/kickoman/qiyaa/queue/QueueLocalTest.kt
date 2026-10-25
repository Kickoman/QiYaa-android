package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
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
class QueueLocalTest {
    @Test
    fun `LOCAL-08 CONT-05 a local track sends no play-audio and no feedback, and never becomes a seed`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.controller.setQueue(
                listOf(yandex("1"), local("42")),
                "Mix",
                false,
                autoplay = true,
                sourceId = null,
            )
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.startPlaying()
            runCurrent()
            assertEquals(listOf("1" to "play-1"), h.source.reports)
            assertEquals(emptyList<String>(), h.source.feedback)
            assertEquals(listOf("1"), h.store.snapshot()?.history)
        }

    @Test
    fun `CONT-12 a pick of local files has no wave from history, and its flag cannot be switched on`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.controller.loadSource("Files", waveFromHistory = false) { listOf(local("42"), local("43")) }
            runCurrent()
            assertFalse(h.controller.state.value.continueWithVibe)
            assertFalse(h.controller.state.value.waveFromHistory)
            h.controller.setContinueWithVibe(true)
            assertFalse(h.controller.state.value.continueWithVibe)
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.engine.isEnded)
            h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            h.controller.loadSource("Liked") { listOf(yandex("1"), yandex("2")) }
            runCurrent()
            assertTrue(h.controller.state.value.continueWithVibe)
            assertTrue(h.controller.state.value.waveFromHistory)
        }

    @Test
    fun `CONT-12 a cleared queue of local files takes the flag by the setting again`() = runTest {
        for (setting in listOf(true, false)) {
            val h = QueueHarness(this, continueWithVibe = setting)
            h.controller.loadSource("Files", waveFromHistory = false) { listOf(local("42")) }
            runCurrent()
            h.controller.clear()
            runCurrent()
            assertTrue(h.controller.state.value.waveFromHistory)
            assertEquals(setting, h.controller.state.value.continueWithVibe)
            assertTrue(h.store.snapshot()?.waveFromHistory ?: true)
        }
    }

    @Test
    fun `CONT-12 a queue without a wave from history stays so after a restart`() = runTest {
        val h = QueueHarness(this, continueWithVibe = true)
        h.controller.loadSource("Files", waveFromHistory = false) { listOf(local("42")) }
        runCurrent()
        val again = QueueHarness(this, store = FakeQueueStore(h.store.text), continueWithVibe = true)
        assertFalse(again.controller.state.value.waveFromHistory)
        assertFalse(again.controller.state.value.continueWithVibe)
        assertEquals(listOf(TrackSource.LOCAL), again.controller.state.value.tracks.map { it.source })
    }

    @Test
    fun `LOCAL-12 a pick over the limit keeps its first tracks and says that the rest was left out`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Files", limit = 2) { listOf(local("1"), local("2"), local("3")) }
            runCurrent()
            assertEquals(listOf("1", "2"), h.engine.ids())
            assertEquals(listOf(QueueEvent.SourceLoaded("Files", 2, cut = true)), h.events)
        }

    @Test
    fun `LOCAL-12 LOCAL-03 a pick with no file the app takes leaves the queue and playback as they were`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Liked") { listOf(yandex("1"), yandex("2")) }
            runCurrent()
            h.engine.startPlaying()
            h.events.clear()
            h.controller.loadSource("Files", waveFromHistory = false, limit = 10_000) { emptyList() }
            runCurrent()
            assertEquals(listOf(QueueEvent.SourceEmpty("Files")), h.events)
            assertEquals(listOf("1", "2"), h.engine.ids())
            assertTrue(h.engine.playing)
            assertTrue(h.controller.state.value.waveFromHistory)
        }

    @Test
    fun `QUAL-05 a local next track is asked about with its source, and its false loads nothing again`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Files") { listOf(local("1"), local("2")) }
            runCurrent()
            val asked = ArrayList<Track>()
            h.controller.onFilesChanged {
                asked += it
                it.source != TrackSource.LOCAL
            }
            assertEquals(listOf(local("2").key), asked.map(Track::key))
            assertEquals(emptyList<String>(), h.engine.commands.filter { it.startsWith("reload") })
        }

    @Test
    fun `LOCAL-08 a local track is never liked or disliked, a dislike of Yandex's 42 skips no local 42`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Files") { listOf(local("42"), local("43")) }
            runCurrent()
            h.controller.setLiked(local("42"), true)
            h.controller.dislike(local("42"))
            h.controller.dislike(yandex("42"))
            runCurrent()
            assertEquals(
                listOf("dislike 42"),
                h.source.calls.filter {
                    it.startsWith("setLiked") ||
                        it.startsWith("dislike")
                },
            )
            assertEquals(0, h.engine.currentIndex)
        }

    @Test
    fun `LOCAL-14 a restored queue is cleared on request, one that another source replaced is not`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Files") { listOf(local("42"), local("43")) }
            runCurrent()
            val restored = QueueHarness(this, store = FakeQueueStore(h.store.text))
            assertTrue(restored.controller.clearIfRestored())
            assertEquals(emptyList<Track>(), restored.controller.state.value.tracks)
            val replaced = QueueHarness(this, store = FakeQueueStore(h.store.text))
            replaced.controller.loadSource("Liked") { listOf(yandex("1")) }
            runCurrent()
            assertFalse(replaced.controller.clearIfRestored())
            assertEquals(listOf("1"), replaced.controller.state.value.tracks.map { it.id })
            assertFalse(QueueHarness(this).controller.clearIfRestored())
        }

    @Test
    fun `LOCAL-14 HOST-23 a restored queue is not cleared during a jam, nor after one`() = runTest {
        val h = QueueHarness(this)
        h.controller.loadSource("Files") { listOf(local("42"), local("43")) }
        runCurrent()
        val during = QueueHarness(this, store = FakeQueueStore(h.store.text))
        during.controller.startJam(Silent, waveFeedback = false)
        assertFalse(during.controller.clearIfRestored())
        assertEquals(listOf("42", "43"), during.controller.state.value.tracks.map { it.id })
        val after = QueueHarness(this, store = FakeQueueStore(h.store.text))
        after.controller.startJam(Silent, waveFeedback = false, afterRestart = true)
        after.controller.onJamQueue(listOf(JamEntry("i1", yandex("X"), "g1")), emptyList(), 0)
        after.controller.endJam()
        runCurrent()
        val left = after.controller.state.value.tracks.map { it.id }
        assertFalse(after.controller.clearIfRestored())
        assertEquals(left, after.controller.state.value.tracks.map { it.id })
    }

    private object Silent : JamPlaybackListener {
        override fun onItemStarted(itemId: String) = Unit

        override fun onPlayback(playback: JamPlayback) = Unit

        override fun onRemoveItems(itemIds: List<String>) = Unit
    }

    private fun yandex(id: String) = Track(id = id, title = "T$id")

    private fun local(id: String) = Track(id = id, title = "Свой $id", source = TrackSource.LOCAL)
}
