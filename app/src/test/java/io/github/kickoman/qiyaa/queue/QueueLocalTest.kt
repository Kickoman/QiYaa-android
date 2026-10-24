package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
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
            runCurrent()
            assertEquals(emptyList<String>(), h.source.calls.filter { it.startsWith("startWave") })
            h.controller.loadSource("Liked") { listOf(yandex("1"), yandex("2")) }
            runCurrent()
            assertTrue(h.controller.state.value.continueWithVibe)
            assertTrue(h.controller.state.value.waveFromHistory)
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
    fun `QUAL-05 a local next track is never asked about or loaded again`() = runTest {
        val h = QueueHarness(this)
        h.controller.loadSource("Files") { listOf(local("1"), local("2")) }
        runCurrent()
        val asked = ArrayList<String>()
        h.controller.onFilesChanged {
            asked += it
            true
        }
        assertEquals(emptyList<String>(), asked)
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

    private fun yandex(id: String) = Track(id = id, title = "T$id")

    private fun local(id: String) = Track(id = id, title = "Свой $id", source = TrackSource.LOCAL)
}
