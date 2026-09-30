package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueJamTest {
    @Test
    fun `HOST-14 a jam keeps the playing track, reports it as wave, and drops the rest of the old queue`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.setQueue(
                tracks("a", "b", "c", "d"),
                "Likes",
                isWave = false,
                autoplay = true,
                sourceId = null,
            )
            h.engine.seekTo(1)
            h.engine.startPlaying()
            val jam = h.startJam()
            assertEquals(listOf("a", "b"), h.engine.ids())
            assertEquals(listOf(JamSlot.Other, JamSlot.Other), h.controller.state.value.jamSlots)
            assertEquals(JamPlayback.Kind.WAVE, jam.playbacks.last().kind)
            assertEquals("b", jam.playbacks.last().track?.id)
        }

    @Test
    fun `HOST-01 HOST-02 the jam part follows the state with the smallest change, keeping the next track`() =
        runTest {
            val h = QueueHarness(this)
            val jam = h.startJam()
            h.state(item("i1", "A"), item("i2", "B"), item("i3", "C"))
            assertEquals(listOf("A", "B", "C"), h.engine.ids())
            assertEquals(listOf("i1"), jam.started)
            h.state(item("i2", "B"), item("i3", "C"))
            h.engine.commands.clear()
            h.state(item("i2", "B"), item("i4", "D"), item("i5", "E"))
            assertEquals(listOf("A", "B", "D", "E"), h.engine.ids())
            assertEquals(listOf("remove 2", "insert 2 [D, E]"), h.engine.commands)
            assertEquals(
                listOf(
                    JamSlot.Item("i1", "g1"),
                    JamSlot.Item("i2", "g1"),
                    JamSlot.Item("i4", "g1"),
                    JamSlot.Item("i5", "g1"),
                ),
                h.controller.state.value.jamSlots,
            )
        }

    @Test
    fun `HOST-03 a new next track replaces the old one`() = runTest {
        val h = QueueHarness(this)
        h.startJam()
        h.state(item("i1", "A"), item("i2", "B"), item("i3", "C"))
        h.state(item("i2", "B"), item("i3", "C"))
        h.engine.commands.clear()
        h.state(item("i3", "C"), item("i2", "B"))
        assertEquals(listOf("A", "C", "B"), h.engine.ids())
        assertEquals(listOf("remove 2", "remove 1", "insert 1 [C, B]"), h.engine.commands)
    }

    @Test
    fun `HOST-04 the current item is never put into the tail again`() = runTest {
        val h = QueueHarness(this)
        h.startJam()
        h.state(item("i1", "A"), item("i2", "B"))
        h.state(item("i1", "A"), item("i2", "B"))
        assertEquals(listOf("A", "B"), h.engine.ids())
    }

    @Test
    fun `HOST-05 HOST-06 the next item reports started, then playing, and pause and every 10 s report too`() =
        runTest {
            val h = QueueHarness(this)
            val jam = h.startJam()
            h.state(item("i1", "A"), item("i2", "B"))
            h.engine.startPlaying()
            h.state(item("i2", "B"))
            h.engine.finishTrack()
            assertEquals(listOf("i1", "i2"), jam.started)
            assertEquals(
                JamPlayback(JamPlayback.Kind.ITEM, "i2", track("B"), 0, paused = false),
                jam.playbacks.last(),
            )
            val reports = jam.playbacks.size
            advanceTimeBy(QueueController.PLAYING_REPORT_MS + 1)
            runCurrent()
            assertEquals(reports + 1, jam.playbacks.size)
            h.engine.stopPlaying()
            assertTrue(jam.playbacks.last().paused)
        }

    @Test
    fun `HOST-07 previous restarts the current track`() = runTest {
        val h = QueueHarness(this)
        h.startJam()
        h.state(item("i1", "A"), item("i2", "B"))
        h.state(item("i2", "B"))
        h.engine.finishTrack()
        h.engine.positionMs = 1_000
        h.controller.previous()
        assertEquals(1, h.engine.currentIndex)
        assertEquals("seek to 0", h.engine.commands.last())
    }

    @Test
    fun `HOST-08 an item that arrives after the player stopped plays at once`() = runTest {
        val h = QueueHarness(this)
        val jam = h.startJam()
        h.state(item("i1", "A"))
        h.state()
        h.engine.finishTrack()
        assertTrue(h.engine.isEnded)
        h.state(item("i2", "B"))
        assertEquals(1, h.engine.currentIndex)
        assertEquals(listOf("i1", "i2"), jam.started)
        assertTrue(h.engine.playWhenReady)
    }

    @Test
    fun `HOST-09 a broken last jam track waits for the jam wave and goes on with it`() = runTest {
        val h = QueueHarness(this)
        h.startJam()
        val reply = CompletableDeferred<WaveBatch>()
        h.source.onStartWave = { reply.await() }
        h.state(item("i1", "A"), seeds = listOf("track:A"), seedsVersion = 1)
        runCurrent()
        h.events.clear()
        h.engine.commands.clear()
        h.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertEquals(listOf(QueueEvent.Failed(Stage.PLAYBACK, ErrorKind.TrackUnplayable)), h.events)
        assertEquals(emptyList<String>(), h.engine.commands)
        reply.complete(WaveBatch("SJ", "B1", tracks("w1")))
        runCurrent()
        assertEquals(listOf("insert 1 [w1]", "seek 1", "prepare", "play"), h.engine.commands)
    }

    @Test
    fun `HOST-10 HOST-11 HOST-12 the jam wave, its deferred tracks, and a new session for new seeds`() =
        runTest {
            val h = QueueHarness(this)
            val jam = h.startJam()
            h.source.onStartWave = { seeds -> WaveBatch("S${seeds.size}", "B1", tracks("w1", "w2", "w3")) }
            h.source.onMoreWave = { _, _ -> WaveBatch("", "B2", tracks("w4", "w5")) }
            h.state(item("i1", "A"), seeds = listOf("track:A"), seedsVersion = 1)
            h.state(seeds = listOf("track:A"), seedsVersion = 1)
            runCurrent()
            assertEquals(listOf("startWave [track:A]"), h.source.calls)
            assertEquals(listOf("A", "w1", "w2", "w3"), h.engine.ids())
            h.engine.finishTrack()
            assertEquals(JamPlayback.Kind.WAVE, jam.playbacks.last().kind)
            assertEquals(listOf("i1"), jam.started)

            h.state(item("i2", "B"), seeds = listOf("track:A"), seedsVersion = 1)
            assertEquals(listOf("A", "w1", "B", "w2", "w3"), h.engine.ids())
            assertEquals(1, h.engine.currentIndex)
            h.engine.finishTrack()
            h.state(seeds = listOf("track:A"), seedsVersion = 1)
            runCurrent()
            assertEquals(listOf("A", "w1", "B", "w2", "w3"), h.engine.ids())
            assertEquals(1, h.source.calls.size)

            // New seeds: once C is the last jam item, the deferred w2 and w3 go and a new session starts.
            h.state(item("i3", "C"), seeds = listOf("track:C", "track:A"), seedsVersion = 2)
            assertEquals(listOf("A", "w1", "B", "C", "w2", "w3"), h.engine.ids())
            h.engine.finishTrack()
            h.state(seeds = listOf("track:C", "track:A"), seedsVersion = 2)
            runCurrent()
            assertEquals("startWave [track:C, track:A]", h.source.calls.last())
            assertEquals(listOf("A", "w1", "B", "C", "w1", "w2", "w3"), h.engine.ids())
            h.engine.finishTrack()
            assertEquals(4, h.engine.currentIndex)
        }

    @Test
    fun `HOST-13 without seeds the wave starts from the current track, and with nothing played it is idle`() =
        runTest {
            val idle = QueueHarness(this)
            val quiet = idle.startJam()
            idle.state()
            assertEquals(JamPlayback.Kind.IDLE, quiet.playbacks.last().kind)

            val h = QueueHarness(this)
            h.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2")) }
            h.controller.setQueue(tracks("x"), "Likes", isWave = false, autoplay = true, sourceId = null)
            h.startJam()
            h.state()
            runCurrent()
            assertEquals(listOf("startWave [track:x]"), h.source.calls)
        }

    @Test
    fun `HOST-15 HOST-16 no play reports for jam tracks, and wave feedback only to the jam wave when on`() =
        runTest {
            for (waveFeedback in listOf(true, false)) {
                val h = QueueHarness(this)
                h.startJam(waveFeedback)
                h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
                h.state(item("i1", "A"), seeds = listOf("track:A"), seedsVersion = 1)
                h.engine.startPlaying()
                h.state(seeds = listOf("track:A"), seedsVersion = 1)
                runCurrent()
                h.engine.finishTrack()
                runCurrent()
                assertEquals(emptyList<Pair<String, String>>(), h.source.reports)
                if (waveFeedback) {
                    assertEquals(
                        listOf("radioStarted SJ/track:A/B1 - 0.0", "trackStarted SJ/track:A/B1 w1 0.0"),
                        h.source.feedback,
                    )
                } else {
                    assertEquals(emptyList<String>(), h.source.feedback)
                }
            }
        }

    @Test
    fun `HOST-18 HOST-19 a guest's skip moves on only for the current item`() = runTest {
        val h = QueueHarness(this)
        h.startJam()
        h.state(item("i1", "A"), item("i2", "B"))
        h.controller.jamSkip("i2")
        assertEquals(0, h.engine.currentIndex)
        h.controller.jamSkip("i1")
        assertEquals(1, h.engine.currentIndex)
    }

    @Test
    fun `HOST-21 sources and queue edits are refused while the jam lasts`() = runTest {
        val h = QueueHarness(this)
        h.startJam()
        h.state(item("i1", "A"))
        h.controller.loadSource("Likes") { tracks("x") }
        h.controller.playWave(listOf("user:onyourwave"), "My Wave")
        h.controller.search("x", "x")
        h.controller.removeIndices(setOf(0))
        h.controller.clear()
        runCurrent()
        assertEquals(listOf("A"), h.engine.ids())
        assertEquals(5, h.events.count { it == QueueEvent.JamActive })
        // Only the jam's own wave, started from the current track while there are no seeds.
        assertEquals(listOf("startWave [track:A]"), h.source.calls)
    }

    @Test
    fun `HOST-32 HOST-33 the end keeps jam items as ordinary tracks, drops the wave, and learning returns`() =
        runTest {
            val h = QueueHarness(this)
            h.startJam()
            h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            h.state(item("i1", "A"), seeds = listOf("track:A"), seedsVersion = 1)
            h.engine.startPlaying()
            h.state(seeds = listOf("track:A"), seedsVersion = 1)
            runCurrent()
            h.state(item("i2", "B"), seeds = listOf("track:B", "track:A"), seedsVersion = 2)
            assertEquals(listOf("A", "B", "w1", "w2"), h.engine.ids())
            h.controller.endJam()
            assertFalse(h.controller.isJamActive)
            assertEquals(listOf("A", "B"), h.engine.ids())
            assertNull(h.controller.state.value.jamSlots)
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("B"), h.source.reports.map { it.first })
        }

    @Test
    fun `a stored queue keeps its jam slots, and a new jam picks them up`() = runTest {
        val first = QueueHarness(this)
        first.startJam()
        first.state(item("i1", "A"), item("i2", "B"))
        runCurrent()
        val restored = QueueHarness(this, store = first.store)
        assertEquals(
            listOf(JamSlot.Item("i1", "g1"), JamSlot.Item("i2", "g1")),
            restored.controller.state.value.jamSlots,
        )
        assertEquals(listOf("A", "B"), restored.engine.ids())
        restored.startJam()
        assertEquals(listOf("A", "B"), restored.engine.ids())
        restored.controller.endJam()
        assertNull(restored.controller.state.value.jamSlots)
    }

    private class Recorder : JamPlaybackListener {
        val started = mutableListOf<String>()
        val playbacks = mutableListOf<JamPlayback>()

        override fun onItemStarted(itemId: String) {
            started += itemId
        }

        override fun onPlayback(playback: JamPlayback) {
            playbacks += playback
        }
    }

    private fun item(itemId: String, trackId: String) = JamEntry(itemId, track(trackId), "g1")

    private fun QueueHarness.startJam(waveFeedback: Boolean = false): Recorder =
        Recorder().also { controller.startJam("Jam", it, waveFeedback) }

    private fun QueueHarness.state(
        vararg entries: JamEntry,
        seeds: List<String> = emptyList(),
        seedsVersion: Int = 0,
    ) = controller.onJamQueue(entries.toList(), seeds, seedsVersion)
}
