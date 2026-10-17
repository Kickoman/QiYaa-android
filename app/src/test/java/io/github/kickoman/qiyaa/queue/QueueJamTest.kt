package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.NetworkLoss
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
    fun `the selection stays on the tracks that stay when the jam part changes`() = runTest {
        val h = QueueHarness(this)
        h.startJam()
        h.state(item("i1", "A"), item("i2", "B"), item("i3", "C"), item("i4", "D"))
        h.controller.toggleSelected(1)
        h.controller.toggleSelected(3)
        h.state(item("i2", "B"), item("i5", "E"))
        assertEquals(listOf("A", "B", "E"), h.engine.ids())
        assertEquals(setOf(1), h.controller.state.value.selected)
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
    fun `HOST-06 a seek reports the new position, also while paused`() = runTest {
        val h = QueueHarness(this)
        val jam = h.startJam()
        h.state(item("i1", "A"), item("i2", "B"))
        h.engine.startPlaying()
        h.engine.seekToPosition(42_000)
        assertEquals(
            JamPlayback(
                JamPlayback.Kind.ITEM,
                "i1",
                track("A"),
                42_000,
                paused = false,
                nextTrack = track("B"),
            ),
            jam.playbacks.last(),
        )
        h.engine.stopPlaying()
        val reports = jam.playbacks.size
        h.engine.seekToPosition(10_000)
        assertEquals(reports + 1, jam.playbacks.size)
        assertEquals(
            JamPlayback(
                JamPlayback.Kind.ITEM,
                "i1",
                track("A"),
                10_000,
                paused = true,
                nextTrack = track("B"),
            ),
            jam.playbacks.last(),
        )
    }

    @Test
    fun `HOST-07 Previous in a jam is a seek`() = runTest {
        val h = QueueHarness(this)
        val jam = h.startJam()
        h.state(item("i1", "A"), item("i2", "B"))
        h.engine.startPlaying()
        h.state(item("i2", "B"))
        h.engine.finishTrack()
        h.engine.positionMs = 1_000
        val reports = jam.playbacks.size
        h.controller.previous()
        assertEquals(1, h.engine.currentIndex)
        assertEquals("seek to 0", h.engine.commands.last())
        assertEquals(listOf("i1", "i2"), jam.started)
        assertEquals(reports + 1, jam.playbacks.size)
        assertEquals(
            JamPlayback(JamPlayback.Kind.ITEM, "i2", track("B"), 0, paused = false),
            jam.playbacks.last(),
        )
    }

    @Test
    fun `HOST-07 HOST-16 Previous on a jam wave track sends no skip and no second trackStarted`() = runTest {
        val h = QueueHarness(this)
        h.startJam(waveFeedback = true)
        h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
        h.state(item("i1", "A"), seeds = listOf("track:A"), seedsVersion = 1)
        h.engine.startPlaying()
        h.state(seeds = listOf("track:A"), seedsVersion = 1)
        runCurrent()
        h.engine.finishTrack()
        runCurrent()
        h.engine.positionMs = 10_000
        h.controller.previous()
        h.engine.startPlaying()
        runCurrent()
        assertEquals("seek to 0", h.engine.commands.last())
        assertEquals(
            listOf("radioStarted SJ/track:A/B1 - 0.0", "trackStarted SJ/track:A/B1 w1 0.0"),
            h.source.feedback,
        )
        assertEquals(emptyList<Pair<String, String>>(), h.source.reports)
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
    fun `ERR-09 a jam item that waits for the network sends started once`() = runTest {
        for (loss in NetworkLoss.entries) {
            val h = QueueHarness(this)
            val jam = h.startJam()
            h.state(item("i1", "A"), item("i2", "B"))
            h.engine.startPlaying()
            advanceTimeBy(10_000)
            h.engine.commands.clear()
            h.network.value = false
            loss.hit(h.engine)
            assertTrue(loss.name, jam.playbacks.last().paused)
            advanceTimeBy(60_000)
            h.network.value = true
            runCurrent()
            assertEquals(loss.name, listOf("prepare"), h.engine.commands)
            h.engine.startPlaying()
            assertEquals(loss.name, listOf("i1"), jam.started)
            assertEquals(
                loss.name,
                JamPlayback(
                    JamPlayback.Kind.ITEM,
                    "i1",
                    track("A"),
                    0,
                    paused = false,
                    nextTrack = track("B"),
                ),
                jam.playbacks.last(),
            )
        }
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
    fun `HOST-14 a load-more reply in flight at the jam start is dropped, with no JamActive and no seek`() =
        runTest {
            val h = QueueHarness(this)
            val reply = CompletableDeferred<WaveBatch>()
            h.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
            h.source.onMoreWave = { _, _ -> reply.await() }
            h.controller.playWave(listOf("user:onyourwave"), "My Wave")
            runCurrent()
            h.engine.startPlaying()
            repeat(3) { h.engine.finishTrack() }
            runCurrent()
            assertTrue(h.engine.isEnded)
            assertTrue(h.controller.state.value.loadingMore)
            h.startJam()
            h.engine.commands.clear()
            reply.complete(WaveBatch("S1", "B2", tracks("m1", "m2")))
            runCurrent()
            assertEquals(listOf("w1", "w2", "w3"), h.engine.ids())
            assertEquals(emptyList<String>(), h.engine.commands)
            assertFalse(h.events.contains(QueueEvent.JamActive))
            assertFalse(h.controller.state.value.loadingMore)
        }

    @Test
    fun `a stored jam keeps its jam wave tracks as continuation entries, which never come from the head`() =
        runTest {
            val first = QueueHarness(this)
            first.startJam()
            first.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            first.state(item("i1", "A"), seeds = listOf("track:A"), seedsVersion = 1)
            first.state(seeds = listOf("track:A"), seedsVersion = 1)
            runCurrent()
            assertEquals(listOf("A", "w1", "w2"), first.engine.ids())
            val parts = listOf(EntryPart.HEAD, EntryPart.CONTINUATION, EntryPart.CONTINUATION)
            assertEquals(parts, first.controller.state.value.entries.map { it.part })
            assertEquals(parts, first.store.snapshot()!!.parts)
            val restored = QueueHarness(this, store = first.store)
            assertEquals(parts, restored.controller.state.value.entries.map { it.part })
            restored.startJam()
            assertEquals(parts, restored.controller.state.value.entries.map { it.part })
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

    @Test
    fun `HOST-14 CONT-04 no vibe from history is asked during a jam`() = runTest {
        val h = QueueHarness(this, continueWithVibe = true)
        h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
        h.controller.loadSource("Likes") { tracks("a", "b", "c", "d") }
        runCurrent()
        h.engine.startPlaying()
        h.startJam()
        assertTrue(h.controller.state.value.continueWithVibe)
        h.state(item("i1", "X"), item("i2", "Y"), seeds = listOf("genre:jam"), seedsVersion = 1)
        h.engine.finishTrack()
        h.state(item("i2", "Y"), seeds = listOf("genre:jam"), seedsVersion = 1)
        h.engine.finishTrack()
        h.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertEquals(listOf("startWave [genre:jam]"), h.source.calls)
    }

    @Test
    fun `HOST-14 a vibe reply in flight at the jam start is dropped, with no JamActive and no seek`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            val reply = CompletableDeferred<WaveBatch>()
            h.source.onStartWave = { reply.await() }
            h.controller.loadSource("Likes") { tracks("a", "b") }
            runCurrent()
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.controller.state.value.loadingMore)
            h.startJam()
            h.engine.commands.clear()
            reply.complete(WaveBatch("V1", "B1", tracks("v1")))
            runCurrent()
            assertEquals(listOf("a", "b"), h.engine.ids())
            assertEquals(emptyList<String>(), h.engine.commands)
            assertEquals(emptyList<String>(), h.source.feedback)
            assertFalse(h.events.contains(QueueEvent.JamActive))
            assertFalse(h.controller.state.value.loadingMore)
        }

    @Test
    fun `HOST-32 CONT-05 after the jam the flag and the history go on`() = runTest {
        val h = QueueHarness(this, continueWithVibe = true)
        h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
        h.controller.loadSource("Likes") { tracks("a", "b", "c") }
        runCurrent()
        h.engine.startPlaying()
        h.startJam()
        h.state(item("i1", "X"))
        h.controller.endJam()
        assertTrue(h.controller.state.value.continueWithVibe)
        assertEquals(listOf("a", "X"), h.engine.ids())
        h.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("startWave [track:X, track:a]"), h.source.calls)
    }

    @Test
    fun `HOST-14 HOST-32 a vibe request in flight at the jam start does not stop the vibe after the jam`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            h.controller.loadSource("Likes") { tracks("a", "b") }
            runCurrent()
            h.engine.startPlaying()
            runCurrent()
            assertEquals(listOf("startWave [track:a]"), h.source.calls)
            h.startJam()
            h.state(item("i1", "X"))
            h.controller.endJam()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("startWave [track:a]", "startWave [track:X, track:a]"), h.source.calls)
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
