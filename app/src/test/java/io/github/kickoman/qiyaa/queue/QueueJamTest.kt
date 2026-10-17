package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.NetworkLoss
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.WaveBatch
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueJamTest {
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
            assertEquals(listOf("i1" to "g1", "i2" to "g1", "i4" to "g1", "i5" to "g1"), h.items())
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
        val h = QueueHarness(this, continueWithVibe = true)
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
        val h = QueueHarness(this, continueWithVibe = true)
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
        assertEquals(listOf("append [w1]", "seek 1", "prepare", "play"), h.engine.commands)
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
    fun `HOST-15 HOST-16 no play reports for jam tracks, and wave feedback only to the jam wave when on`() =
        runTest {
            for (waveFeedback in listOf(true, false)) {
                val h = QueueHarness(this, continueWithVibe = true)
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
    fun `HOST-17 TR-10 in a jam another line only dislikes, and the current one dislikes and skips`() =
        runTest {
            val h = QueueHarness(this)
            val jam = h.startJam()
            h.state(item("i1", "A"), item("i2", "B"), item("i3", "C"))
            h.engine.startPlaying()
            h.controller.dislike(track("C"))
            runCurrent()
            assertEquals(0, h.engine.currentIndex)
            assertEquals(QueueEvent.Disliked, h.events.last())
            assertEquals(listOf("i1"), jam.started)
            h.controller.dislike(track("A"))
            runCurrent()
            assertEquals(1, h.engine.currentIndex)
            assertEquals(QueueEvent.DislikedAndSkipped, h.events.last())
            assertEquals(listOf("i1", "i2"), jam.started)
            assertEquals(listOf("dislike C", "dislike A"), h.source.calls)
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
        assertEquals(emptyList<String>(), h.source.calls)
    }

    @Test
    fun `HOST-32 HOST-33 the end keeps jam items as ordinary tracks, drops the wave, and learning returns`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
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
            assertTrue(h.items().none { it.first != null })
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("B"), h.source.reports.map { it.first })
        }

    @Test
    fun `HOST-14 WAVE-08 a load-more reply in flight at the jam start is the base's, appended and played`() =
        runTest {
            val h = QueueHarness(this)
            val reply = CompletableDeferred<WaveBatch>()
            val replies = ArrayDeque(listOf(reply))
            h.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
            h.source.onMoreWave = { _, _ -> (replies.removeFirstOrNull() ?: CompletableDeferred()).await() }
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
            assertEquals(listOf("w1", "w2", "w3", "m1", "m2"), h.engine.ids())
            assertEquals(listOf("append [m1, m2]", "seek 3", "prepare", "play"), h.engine.commands)
            assertEquals(3, h.engine.currentIndex)
            assertFalse(h.events.contains(QueueEvent.JamActive))
        }

    @Test
    fun `a stored jam keeps its jam wave tracks as continuation entries, which never come from the head`() =
        runTest {
            val first = QueueHarness(this, continueWithVibe = true)
            first.startJam()
            first.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            first.state(item("i1", "A"), seeds = listOf("track:A"), seedsVersion = 1)
            first.state(seeds = listOf("track:A"), seedsVersion = 1)
            runCurrent()
            assertEquals(listOf("A", "w1", "w2"), first.engine.ids())
            val parts = listOf(EntryPart.ITEM, EntryPart.CONTINUATION, EntryPart.CONTINUATION)
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
        assertEquals(listOf("i1" to "g1", "i2" to "g1"), restored.items())
        assertEquals(listOf("A", "B"), restored.engine.ids())
        restored.startJam()
        assertEquals(listOf("A", "B"), restored.engine.ids())
        restored.controller.endJam()
        assertTrue(restored.items().none { it.first != null })
    }

    @Test
    fun `HOST-14 CONT-04 in a jam the vibe from history asks with two tracks left and the state's seeds`() =
        runTest {
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
            assertEquals("b", h.currentId())
            assertEquals(emptyList<String>(), h.source.calls)
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("startWave [genre:jam]"), h.source.calls)
        }

    @Test
    fun `HOST-14 a vibe reply in flight at the jam start is the base's and plays, with no JamActive`() =
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
            assertEquals(listOf("a", "b", "v1"), h.engine.ids())
            assertEquals(listOf("append [v1]", "seek 2", "prepare", "play"), h.engine.commands)
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
        assertEquals(listOf("a", "X", "b", "c"), h.engine.ids())
        h.engine.finishTrack()
        runCurrent()
        assertEquals(emptyList<String>(), h.source.calls)
        h.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("startWave [track:b, track:X, track:a]"), h.source.calls)
    }

    @Test
    fun `HOST-14 HOST-32 a vibe request in flight at the jam start is the base's and is not asked twice`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            val reply = CompletableDeferred<WaveBatch>()
            h.source.onStartWave = { reply.await() }
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
            assertEquals(listOf("startWave [track:a]"), h.source.calls)
            reply.complete(WaveBatch("V1", "B1", tracks("v1")))
            runCurrent()
            assertEquals(listOf("a", "X", "b", "v1"), h.engine.ids())
            assertEquals(listOf("radioStarted V1/track:a/B1 - 0.0"), h.source.feedback)
        }

    @Test
    fun `HOST-14 the current track plays on as wave, and the rest stays as the base with its modes`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", "d")
            h.engine.seekTo(1)
            h.controller.setShuffle(true)
            h.controller.setRepeat(true)
            h.engine.startPlaying()
            h.engine.commands.clear()
            val before = h.controller.state.value
            val jam = h.startJam()
            assertEquals(before, h.controller.state.value)
            assertEquals("Likes", h.controller.state.value.title)
            assertTrue(h.controller.state.value.continueWithVibe)
            assertEquals(emptyList<String>(), h.engine.commands)
            assertEquals(PlayModes(shuffle = true, repeat = true, locked = false), h.controller.modes.value)
            assertTrue(h.engine.shuffleEnabled)
            assertTrue(h.engine.repeatEnabled)
            assertEquals(listOf(1, 0, 2, 3), h.engine.playOrder())
            assertEquals(base("b", next = "a"), jam.playbacks.last())
        }

    @Test
    fun `HOST-14 HOST-12 items go in front of the rest of the base, which keeps its order`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", "c", "d", continueWithVibe = false)
        h.engine.seekTo(1)
        h.engine.startPlaying()
        val jam = h.startJam()
        h.engine.commands.clear()
        h.state(item("i1", "X"), item("i2", "Y"))
        assertEquals(listOf("a", "b", "X", "Y", "c", "d"), h.engine.ids())
        assertEquals(listOf("insert 2 [X, Y]"), h.engine.commands)
        assertEquals(base("b", next = "X"), jam.playbacks.last())
        assertEquals(listOf("X"), h.playOn(1))
        h.state(item("i2", "Y"))
        assertEquals(listOf("Y"), h.playOn(1))
        h.state()
        assertEquals(listOf("c", "d"), h.playOn(2))
        assertEquals(listOf("i1", "i2"), jam.started)
    }

    @Test
    fun `HOST-14 an empty queue is an empty base, and the flag of the setting decides`() = runTest {
        for (flag in listOf(true, false)) {
            val h = QueueHarness(this, continueWithVibe = flag)
            h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            val jam = h.startJam()
            assertEquals(flag, h.controller.state.value.continueWithVibe)
            assertEquals(JamPlayback.Kind.IDLE, jam.playbacks.last().kind)
            h.state(item("i1", "A"), seeds = listOf("genre:jam"), seedsVersion = 1)
            assertEquals(listOf("insert 0 [A]", "prepare", "play"), h.engine.commands)
            assertEquals(listOf("i1"), jam.started)
            h.engine.startPlaying()
            runCurrent()
            if (flag) {
                assertEquals(listOf("startWave [genre:jam]"), h.source.calls)
                assertEquals(listOf("A", "w1", "w2"), h.engine.ids())
            } else {
                assertEquals(emptyList<String>(), h.source.calls)
                h.engine.finishTrack()
                assertEquals(JamPlayback.Kind.IDLE, jam.playbacks.last().kind)
            }
        }
    }

    @Test
    fun `HOST-14 CONT-10 repeat holds only a head with rows, an empty base asks for the jam vibe`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            h.controller.setRepeat(true)
            assertTrue(h.engine.repeatEnabled)
            h.startJam()
            h.state(item("i1", "A"), seeds = listOf("genre:jam"), seedsVersion = 1)
            runCurrent()
            assertEquals(listOf("startWave [genre:jam]"), h.source.calls)
            assertEquals(listOf("A", "w1", "w2"), h.engine.ids())
            assertFalse(h.engine.repeatEnabled)
        }

    @Test
    fun `HOST-05 HOST-12 an item starts with started, an earlier jam's item without one, behind new ones`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.engine.startPlaying()
            val earlier = h.startJam()
            h.state(item("i1", "X"))
            h.controller.endJam()
            assertEquals(listOf(null to "g1"), h.items().filter { it.second != null })
            val jam = h.startJam()
            h.state(item("i2", "Y"))
            assertEquals(listOf("a", "Y", "X", "b"), h.engine.ids())
            assertEquals(listOf("Y", "X"), h.playOn(2))
            assertEquals(base("X", next = "b"), jam.playbacks.last())
            assertEquals(listOf("b"), h.playOn(1))
            assertEquals(listOf("i2"), jam.started)
            assertEquals(emptyList<String>(), earlier.started)
        }

    @Test
    fun `HOST-10 when the items run out the base plays by its rules, reported as wave, with no started`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"))
            assertEquals(listOf("X"), h.playOn(1))
            assertEquals(JamPlayback.Kind.ITEM, jam.playbacks.last().kind)
            h.state()
            assertEquals(listOf("b"), h.playOn(1))
            assertEquals(base("b", next = "c"), jam.playbacks.last())
            assertEquals(listOf("c"), h.playOn(1))
            h.engine.finishTrack()
            assertTrue(h.engine.isEnded)
            assertEquals(JamPlayback.Kind.IDLE, jam.playbacks.last().kind)
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-10 HOST-12 TR-03 with shuffle the items play right after the current track, then the pass`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", "d", "e", continueWithVibe = false)
            h.engine.seekTo(2)
            h.controller.setShuffle(true)
            h.engine.startPlaying()
            h.startJam()
            h.state(item("i1", "X"), item("i2", "Y"))
            assertEquals(listOf("a", "b", "c", "X", "Y", "d", "e"), h.engine.ids())
            h.controller.next()
            assertEquals("X", h.currentId())
            assertEquals("next", h.engine.commands.last())
            assertEquals(listOf("Y", "a", "b", "d", "e"), h.playOn(5))
            assertFalse(h.engine.commands.contains("pause at end true"))
        }

    @Test
    fun `HOST-10 TR-05 CONT-10 repeat wraps the base's head behind the items, played items stay out`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.controller.setRepeat(true)
            h.engine.startPlaying()
            h.startJam()
            h.state(item("i1", "X"))
            assertEquals(listOf("a", "X", "b", "c"), h.engine.ids())
            assertTrue(h.engine.repeatEnabled)
            assertEquals(listOf("X", "b", "c", "a", "b", "c"), h.playOn(6))
        }

    @Test
    fun `HOST-11 the jam vibe asks with the last state's seeds, and its tracks keep the seeds version`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c")
            h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            h.engine.startPlaying()
            h.startJam()
            h.state(item("i1", "X"), seeds = listOf("genre:jam", "track:X"), seedsVersion = 3)
            h.playOn(2)
            runCurrent()
            assertEquals(listOf("startWave [genre:jam, track:X]"), h.source.calls)
            assertEquals(listOf("a", "X", "b", "c", "w1", "w2"), h.engine.ids())
            val vibe = h.controller.state.value.entries.drop(4)
            assertEquals(listOf(EntryPart.CONTINUATION, EntryPart.CONTINUATION), vibe.map { it.part })
            assertEquals(listOf(3, 3), vibe.map { it.seedsVersion })
        }

    @Test
    fun `HOST-11 CONT-05 without seeds in the state the jam vibe takes the history, items included`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c")
            h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            h.engine.startPlaying()
            h.startJam()
            h.state(item("i1", "X"))
            h.playOn(2)
            runCurrent()
            assertEquals(listOf("startWave [track:b, track:X, track:a]"), h.source.calls)
            assertEquals(listOf(null, null), h.controller.state.value.entries.drop(4).map { it.seedsVersion })
        }

    @Test
    fun `HOST-11 new seeds drop the outdated tail once no item waits and no vibe track plays`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", "c")
        h.source.onStartWave = { seeds ->
            if (seeds == listOf("genre:one")) {
                WaveBatch("S1", "B1", tracks("w1", "w2", "w3"))
            } else {
                WaveBatch("S2", "B1", tracks("w4", "w5"))
            }
        }
        h.engine.startPlaying()
        h.startJam()
        h.state(seeds = listOf("genre:one"), seedsVersion = 1)
        h.playOn(1)
        runCurrent()
        assertEquals(listOf("c", "w1"), h.playOn(2))
        h.state(item("i1", "X"), seeds = listOf("genre:two"), seedsVersion = 2)
        assertEquals(listOf("a", "b", "c", "w1", "X", "w2", "w3"), h.engine.ids())
        assertEquals(listOf("X"), h.playOn(1))
        assertEquals(listOf("a", "b", "c", "w1", "X"), h.engine.ids())
        runCurrent()
        assertEquals(listOf("startWave [genre:one]", "startWave [genre:two]"), h.source.calls)
        assertEquals(listOf("a", "b", "c", "w1", "X", "w4", "w5"), h.engine.ids())
        assertEquals(listOf(2, 2), h.controller.state.value.entries.drop(5).map { it.seedsVersion })
    }

    @Test
    fun `HOST-11 a reply of an outdated session stays out of the queue, and the request goes again`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c")
            val outdated = CompletableDeferred<WaveBatch>()
            h.source.onStartWave = { seeds ->
                if (seeds == listOf("genre:one")) {
                    outdated.await()
                } else {
                    WaveBatch("S2", "B2", tracks("w3", "w4"))
                }
            }
            h.engine.startPlaying()
            h.startJam(waveFeedback = true)
            h.state(seeds = listOf("genre:one"), seedsVersion = 1)
            h.playOn(1)
            runCurrent()
            h.state(seeds = listOf("genre:two"), seedsVersion = 2)
            outdated.complete(WaveBatch("S1", "B1", tracks("w1", "w2")))
            runCurrent()
            assertEquals(listOf("startWave [genre:one]", "startWave [genre:two]"), h.source.calls)
            assertEquals(listOf("a", "b", "c", "w3", "w4"), h.engine.ids())
            assertEquals(listOf("radioStarted S2/genre:two/B2 - 0.0"), h.source.feedback)
        }

    @Test
    fun `HOST-11 seeds drop the history vibe's tail but never the head, and never an own wave`() = runTest {
        val h = QueueHarness.likes(this, "a", "b")
        h.source.onStartWave = { seeds ->
            if (seeds == listOf("genre:jam")) {
                WaveBatch("V2", "B1", tracks("j1", "j2"))
            } else {
                WaveBatch("V1", "B1", tracks("v1", "v2"))
            }
        }
        h.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a", "b", "v1", "v2"), h.engine.ids())
        h.startJam()
        h.state(seeds = listOf("genre:jam"), seedsVersion = 1)
        assertEquals(listOf("a", "b"), h.engine.ids())
        runCurrent()
        assertEquals(listOf("startWave [track:a]", "startWave [genre:jam]"), h.source.calls)
        assertEquals(listOf("a", "b", "j1", "j2"), h.engine.ids())

        val wave = QueueHarness.wave(this, "w1", "w2", "w3")
        wave.startJam()
        wave.state(seeds = listOf("genre:jam"), seedsVersion = 1)
        assertEquals(listOf("w1", "w2", "w3"), wave.engine.ids())
    }

    @Test
    fun `HOST-11 CONT-07 a new seeds version ends the stall, the next item asks and starts by itself`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.source.onStartWave = { seeds ->
                if (seeds == listOf("genre:one")) {
                    throw IOException("offline")
                } else {
                    WaveBatch("S2", "B1", tracks("w1"))
                }
            }
            val jam = h.startJam()
            h.state(item("i1", "A"), seeds = listOf("genre:one"), seedsVersion = 1)
            runCurrent()
            assertEquals(listOf(Stage.WAVE), h.events.filterIsInstance<QueueEvent.Failed>().map { it.stage })
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(JamPlayback.Kind.IDLE, jam.playbacks.last().kind)
            h.state(seeds = listOf("genre:two"), seedsVersion = 2)
            runCurrent()
            assertEquals(1, h.source.calls.size)
            h.state(item("i2", "B"), seeds = listOf("genre:two"), seedsVersion = 2)
            runCurrent()
            assertEquals(listOf("startWave [genre:one]", "startWave [genre:two]"), h.source.calls)
            assertEquals("B", h.currentId())
            assertTrue(h.engine.playWhenReady)
            assertEquals(listOf("A", "B", "w1"), h.engine.ids())
            assertEquals(listOf("i1", "i2"), jam.started)
        }

    @Test
    fun `HOST-11 CONT-07 an outdated-seed session that fails stalls until a new seeds version`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", "c")
        h.source.onStartWave = { seeds ->
            when (seeds) {
                listOf("genre:one") -> WaveBatch("S1", "B1", tracks("w1", "w2"))
                listOf("genre:two") -> throw IOException("offline")
                else -> WaveBatch("S3", "B1", tracks("w3"))
            }
        }
        h.engine.startPlaying()
        h.startJam()
        h.state(seeds = listOf("genre:one"), seedsVersion = 1)
        h.playOn(1)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "w1", "w2"), h.engine.ids())
        h.state(seeds = listOf("genre:two"), seedsVersion = 2)
        runCurrent()
        assertEquals(listOf("a", "b", "c"), h.engine.ids())
        assertEquals(listOf(Stage.WAVE), h.events.filterIsInstance<QueueEvent.Failed>().map { it.stage })
        h.playOn(1)
        runCurrent()
        assertEquals(2, h.source.calls.size)
        h.state(seeds = listOf("genre:three"), seedsVersion = 3)
        runCurrent()
        assertEquals(
            listOf("startWave [genre:one]", "startWave [genre:two]", "startWave [genre:three]"),
            h.source.calls,
        )
        assertEquals(listOf("a", "b", "c", "w3"), h.engine.ids())
    }

    @Test
    fun `HOST-13 TR-04 a base that runs out with the flag off stops idle, and an item then starts`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.playOn(1)
            h.controller.next()
            assertEquals(1, h.engine.currentIndex)
            assertEquals(idle, jam.playbacks.last())
            h.engine.commands.clear()
            h.state(item("i1", "X"))
            assertEquals(listOf("insert 2 [X]", "seek 2", "prepare", "play"), h.engine.commands)
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-13 CONT-06 no seeds in the state or the history, the base stops and the jam is idle`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.controller.setQueue(tracks("a"), "Likes", isWave = false, autoplay = false, sourceId = null)
            val jam = h.startJam()
            h.state()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.engine.isEnded)
            assertEquals(emptyList<String>(), h.source.calls)
            assertEquals(JamPlayback.Kind.IDLE, jam.playbacks.last().kind)
        }

    @Test
    fun `HOST-15 HOST-33 a My Vibe base learns nothing during the jam and learns again after it`() = runTest {
        val h = QueueHarness.wave(this, "w1", "w2", "w3")
        h.engine.startPlaying()
        h.startJam(waveFeedback = true)
        h.state(item("i1", "X"))
        assertEquals(listOf("X", "w2"), h.playOn(2))
        h.controller.endJam()
        assertEquals(listOf("w3"), h.playOn(1))
        runCurrent()
        assertEquals(
            listOf(
                "radioStarted S1/user:onyourwave/B1 - 0.0",
                "trackStarted S1/user:onyourwave/B1 w1 0.0",
                "trackStarted S1/user:onyourwave/B1 w3 0.0",
            ),
            h.source.feedback,
        )
        assertEquals(listOf("w1", "w3"), h.source.reports.map { it.first })
    }

    @Test
    fun `HOST-32 a waiting item behind the current track of a My Vibe base plays after the end`() = runTest {
        val h = QueueHarness.wave(this, "w1", "w2", "w3")
        h.engine.startPlaying()
        val jam = h.startJam()
        h.state(item("i1", "X"))
        h.controller.endJam()
        assertEquals(listOf("w1", "X", "w2", "w3"), h.engine.ids())
        assertEquals(listOf("X", "w2"), h.playOn(2))
        assertEquals(emptyList<String>(), jam.started)
        assertEquals(ItemMark(null, "g1", waiting = false), h.controller.state.value.entries[1].item)
    }

    @Test
    fun `HOST-32 CONT-09 likes with shuffle after the vibe, the head does not play again after the end`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b")
            h.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            h.controller.setShuffle(true)
            h.engine.startPlaying()
            runCurrent()
            assertEquals(listOf("b", "v1"), h.playOn(2))
            h.startJam()
            h.state(item("i1", "X"))
            h.controller.endJam()
            assertEquals(listOf("a", "b", "v1", "X", "v2"), h.engine.ids())
            assertEquals(listOf("X", "v2"), h.playOn(2))
            h.engine.finishTrack()
            assertTrue(h.engine.isEnded)
            assertEquals("v2", h.currentId())
        }

    @Test
    fun `HOST-32 a reply of the ended jam's party session stays out of the queue`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", "c")
        val more = CompletableDeferred<WaveBatch>()
        h.source.onStartWave = { seeds ->
            if (seeds == listOf("genre:jam")) {
                WaveBatch("S1", "B1", tracks("w1", "w2"))
            } else {
                WaveBatch("V2", "B1", tracks("h1"))
            }
        }
        h.source.onMoreWave = { _, _ -> more.await() }
        h.engine.startPlaying()
        h.startJam()
        h.state(seeds = listOf("genre:jam"), seedsVersion = 1)
        h.playOn(1)
        runCurrent()
        h.playOn(2)
        h.controller.endJam()
        assertEquals(listOf("a", "b", "c", "w1"), h.engine.ids())
        more.complete(WaveBatch("S1", "B2", tracks("m1")))
        runCurrent()
        assertEquals(
            listOf(
                "startWave [genre:jam]",
                "moreWave S1 [a, b, c, w1, w2]",
                "startWave [track:c, track:b, track:a]",
            ),
            h.source.calls,
        )
        assertEquals(listOf("a", "b", "c", "w1", "h1"), h.engine.ids())
        assertEquals(listOf("radioStarted V2/track:c/B1 - 0.0"), h.source.feedback)
    }

    @Test
    fun `HOST-32 TR-03 after a jam a tap further down plays that row, then the item left, each once`() =
        runTest {
            val h = leftAfterJam()
            h.engine.seekTo(9)
            val ids = (0..8).map { "h$it" } + "X" + "h9"
            assertEquals(ids, h.engine.ids())
            assertEquals(listOf("X", "h9"), h.playOn(2))
            h.engine.finishTrack()
            assertTrue(h.engine.isEnded)
        }

    @Test
    fun `HOST-32 TR-01 after a jam Previous plays the row before, then the item left, then the row`() =
        runTest {
            val h = leftAfterJam()
            h.controller.previous()
            assertEquals("h2", h.currentId())
            assertEquals(listOf("X", "h3", "h4"), h.playOn(3))
        }

    @Test
    fun `HOST-08 a state before the player is there plays in the server's order once it attaches`() =
        runTest {
            val h = QueueHarness(this, attached = false)
            val jam = h.startJam()
            h.state(item("i1", "A"), item("i2", "B"))
            h.state(item("i1", "A"), item("i2", "B"), item("i3", "C"))
            assertEquals(listOf("A", "B", "C"), h.controller.state.value.tracks.map { it.id })
            h.controller.attach(h.engine)
            assertEquals(listOf("set [A, B, C] play=true"), h.engine.commands)
            assertEquals(listOf("i1"), jam.started)
            assertEquals(listOf("B", "C"), h.playOn(2))
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

    private val idle = JamPlayback(JamPlayback.Kind.IDLE, null, null, 0, paused = true)

    private fun base(trackId: String, next: String?) = JamPlayback(
        JamPlayback.Kind.BASE,
        null,
        track(trackId),
        0,
        paused = false,
        nextTrack = next?.let(::track),
    )

    private fun item(itemId: String, trackId: String) = JamEntry(itemId, track(trackId), "g1")

    private fun QueueHarness.items(): List<Pair<String?, String?>> =
        controller.state.value.entries.map { it.item?.itemId to it.item?.addedBy }

    private fun QueueHarness.currentId(): String = engine.tracks[engine.currentIndex].id

    private fun QueueHarness.playOn(count: Int): List<String> = (1..count).map {
        engine.finishTrack()
        currentId()
    }

    private fun TestScope.leftAfterJam(): QueueHarness {
        val ids = (0..9).map { "h$it" }.toTypedArray()
        val h = QueueHarness.likes(this, *ids, continueWithVibe = false)
        h.engine.seekTo(3)
        h.engine.startPlaying()
        h.startJam()
        h.state(item("i1", "X"))
        h.controller.endJam()
        return h
    }

    private fun QueueHarness.startJam(waveFeedback: Boolean = false): Recorder =
        Recorder().also { controller.startJam(it, waveFeedback) }

    private fun QueueHarness.state(
        vararg entries: JamEntry,
        seeds: List<String> = emptyList(),
        seedsVersion: Int = 0,
    ) = controller.onJamQueue(entries.toList(), seeds, seedsVersion)
}
