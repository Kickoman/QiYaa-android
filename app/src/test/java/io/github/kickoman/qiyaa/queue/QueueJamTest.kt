package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.NetworkLoss
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.SearchResult
import io.github.kickoman.qiyaa.yandex.Track
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
    fun `HOST-21 HOST-36 sources, a search and queue edits act while the jam lasts`() = runTest {
        val h = QueueHarness(this)
        h.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1")) }
        h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        h.source.onSearch = { SearchResult(tracks = tracks("s1")) }
        h.startJam()
        h.state(item("i1", "A"), item("i2", "B"))
        h.controller.loadSource("Likes") { tracks("x") }
        runCurrent()
        assertEquals(listOf("A", "B", "x"), h.engine.ids())
        h.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        assertEquals(listOf("A", "B", "w1"), h.engine.ids())
        h.controller.search("x", "x")
        runCurrent()
        assertEquals(listOf("A", "B", "s1"), h.engine.ids())
        h.controller.removeIndices(setOf(0))
        assertEquals(listOf("B", "s1"), h.engine.ids())
        h.controller.clear()
        runCurrent()
        assertEquals(listOf("B"), h.engine.ids())
        assertEquals(
            listOf(
                QueueEvent.SourceLoaded("Likes", 1),
                QueueEvent.SourceLoading("My Wave"),
                QueueEvent.WaveStarted("My Wave"),
                QueueEvent.SearchStarted("x"),
                QueueEvent.SourceLoaded("x", 1),
            ),
            h.events,
        )
        assertEquals(listOf("startWave [user:onyourwave]", "search x"), h.source.calls)
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
        }

    @Test
    fun `HOST-23 the marks survive a restart, and Yes keeps only the current track and the items`() =
        runTest {
            val first = QueueHarness.likes(this, "a", "b", "c")
            first.engine.startPlaying()
            first.startJam()
            first.state(item("i1", "X"), item("i2", "Y"), seeds = listOf("genre:jam"), seedsVersion = 1)
            first.engine.finishTrack()
            first.engine.positionMs = 4_000
            first.engine.stopPlaying()
            runCurrent()
            assertEquals(listOf("a", "X", "Y", "b", "c"), first.engine.ids())
            val restored = restart(first)
            restored.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            assertEquals(first.controller.state.value.entries, restored.controller.state.value.entries)
            assertEquals("set [a, X, Y, b, c] play=false at 1:4000", restored.engine.commands.last())
            val jam = restored.startJam(afterRestart = true)
            assertEquals(listOf("X", "Y"), restored.engine.ids())
            assertEquals(
                listOf(EntryPart.ITEM, EntryPart.ITEM),
                restored.controller.state.value.entries.map { it.part },
            )
            assertEquals(listOf("i1" to "g1", "i2" to "g1"), restored.items())
            assertEquals("", restored.controller.state.value.title)
            assertTrue(restored.controller.state.value.continueWithVibe)
            assertFalse(restored.engine.playWhenReady)
            assertEquals(
                JamPlayback(JamPlayback.Kind.ITEM, "i1", track("X"), 4_000, true, nextTrack = track("Y")),
                jam.playbacks.last(),
            )
            restored.engine.commands.clear()
            restored.state(item("i2", "Y"), seeds = listOf("genre:jam"), seedsVersion = 1)
            runCurrent()
            assertEquals(emptyList<String>(), restored.engine.commands.withoutPauseAtEnd())
            assertEquals(listOf("startWave [genre:jam]"), restored.source.calls)
            assertEquals(emptyList<String>(), jam.started)
            restored.engine.startPlaying()
            restored.engine.finishTrack()
            assertEquals("Y", restored.currentId())
            assertEquals(listOf("i2"), jam.started)
        }

    @Test
    fun `HOST-32 after No a stored jam's waiting items play first, and the party's vibe tail is gone`() =
        runTest {
            val first = QueueHarness.likes(this, "a", "b")
            first.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            first.engine.startPlaying()
            first.startJam()
            first.state(item("i1", "X"), item("i2", "Y"), seeds = listOf("genre:jam"), seedsVersion = 1)
            first.engine.finishTrack()
            first.state(item("i2", "Y"), seeds = listOf("genre:jam"), seedsVersion = 1)
            first.engine.finishTrack()
            runCurrent()
            first.state(item("i3", "Z"), seeds = listOf("genre:jam"), seedsVersion = 1)
            first.engine.stopPlaying()
            runCurrent()
            assertEquals(listOf("a", "X", "Y", "Z", "b", "w1", "w2"), first.engine.ids())
            val saved = first.store.snapshot()!!
            assertEquals(listOf(null, null, null, null, null, 1, 1), saved.seedsVersions)
            val restored = restart(first)
            restored.source.onStartWave = { WaveBatch("S2", "C1", tracks("n1")) }
            assertEquals("Y", restored.currentId())
            restored.controller.endJam()
            runCurrent()
            assertEquals(listOf("a", "X", "Y", "Z", "b"), restored.engine.ids())
            assertTrue(restored.items().none { it.first != null })
            assertEquals("", restored.store.snapshot()!!.waveSessionId)
            restored.engine.startPlaying()
            assertEquals(listOf("Z", "b"), restored.playOn(2))
            runCurrent()
            assertEquals(listOf("startWave [track:Z, track:Y, track:X, track:a]"), restored.source.calls)
            assertEquals(listOf("n1"), restored.playOn(1))
        }

    @Test
    fun `HOST-23 HOST-36 a stored held item comes back held, before or after attach, until Play`() = runTest {
        for (attachedFirst in listOf(true, false)) {
            val first = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            first.engine.startPlaying()
            first.startJam()
            first.state(item("i1", "X"))
            first.controller.removeIndices(setOf(0))
            runCurrent()
            assertEquals(ItemMark("i1", "g1", waiting = true), first.store.snapshot()!!.items[0])
            val restored =
                QueueHarness(this, attached = attachedFirst, store = FakeQueueStore(first.store.text))
            val jam = restored.startJam(afterRestart = true)
            if (!attachedFirst) restored.controller.attach(restored.engine)
            restored.state(item("i1", "X"))
            assertEquals(listOf("X"), restored.engine.ids())
            assertEquals(emptyList<String>(), jam.started)
            assertEquals(idle, jam.playbacks.last())
            assertFalse(restored.engine.playWhenReady)
            restored.engine.startPlaying()
            assertEquals(listOf("i1"), jam.started)
            assertEquals(JamPlayback.Kind.ITEM, jam.playbacks.last().kind)
        }
    }

    @Test
    fun `HOST-23 a state before attach that drops the held item removes it and holds the next one`() =
        runTest {
            val first = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            first.engine.startPlaying()
            first.startJam()
            first.state(item("i1", "X"), item("i2", "Y"))
            first.controller.removeIndices(setOf(0))
            runCurrent()
            val restored = QueueHarness(this, attached = false, store = FakeQueueStore(first.store.text))
            val jam = restored.startJam(afterRestart = true)
            restored.state(item("i2", "Y"))
            restored.controller.attach(restored.engine)
            assertEquals("set [Y] play=false", restored.engine.commands.first { it.startsWith("set") })
            assertEquals(emptyList<String>(), jam.started)
            restored.engine.startPlaying()
            assertEquals(listOf("i2"), jam.started)
        }

    @Test
    fun `CONT-11 with repeat on, a restored item played in the continuation goes on with the continuation`() =
        runTest {
            val first = QueueHarness.likes(this, "h0", "h1")
            first.source.onStartWave = { WaveBatch("V1", "B1", tracks("c0", "c1", "c2")) }
            first.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            first.engine.startPlaying()
            runCurrent()
            repeat(3) { first.engine.finishTrack() }
            first.startJam()
            first.state(item("i1", "X"))
            first.engine.finishTrack()
            first.controller.setRepeat(true)
            first.engine.stopPlaying()
            runCurrent()
            assertEquals(listOf("h0", "h1", "c0", "c1", "X", "c2"), first.engine.ids())
            val restored = restart(first)
            restored.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            restored.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            assertEquals("X", restored.currentId())
            assertTrue(restored.controller.modes.value.repeat)
            assertFalse(restored.engine.pauseAtEndOfCurrent)
            restored.engine.startPlaying()
            assertEquals(listOf("c2"), restored.playOn(1))
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
    fun `HOST-14 a vibe reply in flight at the jam start is the base's and plays`() = runTest {
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

    @Test
    fun `HOST-21 CONT-01 a pick becomes the base, the current track and the items stay, nothing starts`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", "d", continueWithVibe = false)
            h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            h.engine.seekTo(1)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"), item("i2", "Y"))
            h.continueWithVibe = true
            h.engine.commands.clear()
            h.controller.loadSource("Mix") { tracks("m1", "m2") }
            runCurrent()
            assertEquals(listOf("b", "X", "Y", "m1", "m2"), h.engine.ids())
            assertEquals(listOf("remove 5", "remove 4", "remove 0", "append [m1, m2]"), h.engine.commands)
            val parts =
                listOf(EntryPart.OTHER, EntryPart.ITEM, EntryPart.ITEM, EntryPart.HEAD, EntryPart.HEAD)
            assertEquals(parts, h.controller.state.value.entries.map { it.part })
            assertEquals("Mix", h.controller.state.value.title)
            assertTrue(h.controller.state.value.continueWithVibe)
            assertEquals(QueueEvent.SourceLoaded("Mix", 2), h.events.last())
            assertEquals(base("b", next = "X"), jam.playbacks.last())
            assertEquals(listOf("X", "Y", "m1", "m2"), h.playOn(4))
            assertEquals(listOf("i1", "i2"), jam.started)
        }

    @Test
    fun `HOST-21 TR-04 a pick after the base ran out stays stopped until Next, which plays the new base`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.playOn(1)
            h.engine.finishTrack()
            assertTrue(h.engine.isEnded)
            h.engine.commands.clear()
            h.controller.loadSource("Mix") { tracks("m1") }
            runCurrent()
            assertEquals(listOf("b", "m1"), h.engine.ids())
            assertEquals(listOf("remove 0", "append [m1]"), h.engine.commands)
            assertEquals(idle, jam.playbacks.last())
            h.controller.next()
            assertEquals("m1", h.currentId())
            assertEquals(listOf("prepare", "play"), h.engine.commands.takeLast(2))
        }

    @Test
    fun `HOST-21 HOST-15 HOST-33 a pick of My Vibe becomes the base and sends radioStarted after the end`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2")) }
            h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            h.engine.startPlaying()
            h.startJam(waveFeedback = true)
            h.controller.playWave(listOf("user:onyourwave"), "My Wave")
            runCurrent()
            assertEquals(listOf("a", "w1", "w2"), h.engine.ids())
            assertTrue(h.controller.state.value.isWave)
            assertTrue(h.controller.modes.value.locked)
            assertEquals(
                listOf(QueueEvent.SourceLoading("My Wave"), QueueEvent.WaveStarted("My Wave")),
                h.events,
            )
            assertEquals(listOf("w1"), h.playOn(1))
            runCurrent()
            assertEquals(emptyList<String>(), h.source.feedback)
            h.controller.endJam()
            assertEquals(listOf("w2"), h.playOn(1))
            runCurrent()
            assertEquals(
                listOf(
                    "radioStarted S1/user:onyourwave/B1 - 0.0",
                    "trackStarted S1/user:onyourwave/B1 w2 0.0",
                ),
                h.source.feedback,
            )
        }

    @Test
    fun `HOST-21 SRC-07 WAVE-03 an empty or failed pick leaves the base as it was`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
        h.source.onStartWave = { WaveBatch("S1", "B1", listOf(track("w1", available = false))) }
        h.engine.startPlaying()
        h.startJam()
        h.state(item("i1", "X"))
        h.controller.loadSource("Empty") { listOf(track("x", available = false)) }
        runCurrent()
        h.controller.loadSource("Broken") { throw IOException("offline") }
        runCurrent()
        h.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        assertEquals(listOf("a", "X", "b"), h.engine.ids())
        assertEquals("Likes", h.controller.state.value.title)
        assertEquals(QueueEvent.SourceEmpty("Empty"), h.events.first())
        assertEquals(listOf(Stage.SOURCE), h.events.filterIsInstance<QueueEvent.Failed>().map { it.stage })
        assertEquals(QueueEvent.SourceEmpty("My Wave"), h.events.last())
        assertEquals(emptyList<String>(), h.source.feedback)
    }

    @Test
    fun `HOST-21 WAVE-07 a reply of the old base in flight stays out of the new base`() = runTest {
        val h = QueueHarness.likes(this, "a", "b")
        val reply = CompletableDeferred<WaveBatch>()
        h.source.onStartWave = { reply.await() }
        h.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("startWave [track:a]"), h.source.calls)
        h.startJam()
        h.controller.loadSource("Mix") { tracks("m1", "m2", "m3") }
        runCurrent()
        reply.complete(WaveBatch("V1", "B1", tracks("v1")))
        runCurrent()
        assertEquals(listOf("a", "m1", "m2", "m3"), h.engine.ids())
        assertEquals(listOf("startWave [track:a]"), h.source.calls)
        assertEquals(emptyList<String>(), h.source.feedback)
        assertFalse(h.controller.state.value.loadingMore)
    }

    @Test
    fun `HOST-21 D5 a pick in flight at the jam start and a search during the jam become the base`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.engine.startPlaying()
            val loaded = CompletableDeferred<List<Track>>()
            h.controller.loadSource("Mix") { loaded.await() }
            runCurrent()
            val jam = h.startJam()
            h.state(item("i1", "X"))
            loaded.complete(tracks("m1"))
            runCurrent()
            assertEquals(listOf("a", "X", "m1"), h.engine.ids())
            assertEquals("Mix", h.controller.state.value.title)
            h.source.onSearch = { SearchResult(tracks = tracks("s1", "s2")) }
            h.controller.search("кино", "Search: кино")
            runCurrent()
            assertEquals(listOf("a", "X", "s1", "s2"), h.engine.ids())
            assertEquals(
                listOf(
                    QueueEvent.SourceLoaded("Mix", 1),
                    QueueEvent.SearchStarted("Search: кино"),
                    QueueEvent.SourceLoaded("Search: кино", 2),
                ),
                h.events,
            )
            assertEquals(base("a", next = "X"), jam.playbacks.last())
        }

    @Test
    fun `HOST-36 removing a waiting item sends remove and leaves it to the next state, other rows go now`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"))
            assertEquals(listOf("X"), h.playOn(1))
            h.state(item("i2", "Y"))
            assertEquals(listOf("a", "X", "Y", "b", "c"), h.engine.ids())
            for (index in listOf(0, 2, 4)) h.controller.toggleSelected(index)
            h.engine.commands.clear()
            h.controller.removeIndices(h.controller.state.value.selected)
            assertEquals(listOf(listOf("i2")), jam.removals)
            assertEquals(listOf("remove 4", "remove 0"), h.engine.commands)
            assertEquals(listOf("X", "Y", "b"), h.engine.ids())
            assertEquals(emptySet<Int>(), h.controller.state.value.selected)
            assertEquals("X", h.currentId())
            assertTrue(h.engine.playWhenReady)
            h.state()
            assertEquals(listOf("X", "b"), h.engine.ids())
            assertEquals(listOf("b"), h.playOn(1))
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-36 removing the current track stops, the item behind it waits until Play or Next starts it`() =
        runTest {
            for (start in listOf("play", "next")) {
                val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
                h.engine.startPlaying()
                val jam = h.startJam()
                h.state(item("i1", "X"), item("i2", "Y"))
                h.engine.commands.clear()
                h.controller.removeIndices(setOf(0))
                assertEquals(start, listOf("pause", "remove 0"), h.engine.commands)
                assertEquals(start, listOf("X", "Y", "b"), h.engine.ids())
                assertEquals(start, "X", h.currentId())
                assertTrue(start, h.controller.state.value.entries[0].isWaiting)
                assertFalse(start, h.engine.playWhenReady)
                assertEquals(start, idle, jam.playbacks.last())
                h.state(item("i1", "X"), item("i2", "Y"))
                h.controller.jamSkip("i1")
                assertEquals(start, listOf("X", "Y", "b"), h.engine.ids())
                assertEquals(start, "X", h.currentId())
                assertEquals(start, emptyList<String>(), jam.started)
                if (start == "play") h.engine.startPlaying() else h.controller.next()
                assertEquals(start, "X", h.currentId())
                assertEquals(start, listOf("i1"), jam.started)
                assertFalse(start, h.controller.state.value.entries[0].isWaiting)
                val playback = JamPlayback(
                    JamPlayback.Kind.ITEM,
                    "i1",
                    track("X"),
                    0,
                    paused = start == "next",
                    nextTrack = track("Y"),
                )
                assertEquals(start, playback, jam.playbacks.last())
                if (start == "next") assertEquals(start, "play", h.engine.commands.last())
            }
        }

    @Test
    fun `HOST-36 HOST-17 a held item stays held when the player comes back, and its dislike skips nothing`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"))
            h.controller.removeIndices(setOf(0))
            h.controller.detach(h.engine)
            h.controller.attach(h.engine)
            assertEquals(listOf("X", "b"), h.engine.ids())
            assertTrue(h.controller.state.value.entries[0].isWaiting)
            assertEquals(idle, jam.playbacks.last())
            h.controller.dislike(track("X"))
            runCurrent()
            assertEquals(QueueEvent.Disliked, h.events.last())
            assertEquals("X", h.currentId())
            assertEquals(emptyList<String>(), jam.started)
            h.engine.startPlaying()
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-36 a held item the state drops leaves, the next item is held, a base row waits paused at 0`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"), item("i2", "Y"))
            h.controller.removeIndices(setOf(0))
            h.state(item("i2", "Y"))
            assertEquals(listOf("Y", "b", "c"), h.engine.ids())
            assertTrue(h.controller.state.value.entries[0].isWaiting)
            assertEquals(idle, jam.playbacks.last())
            h.state()
            assertEquals(listOf("b", "c"), h.engine.ids())
            assertEquals(base("b", next = "c").copy(paused = true), jam.playbacks.last())
            h.engine.startPlaying()
            assertEquals(base("b", next = "c"), jam.playbacks.last())
            assertEquals(emptyList<String>(), jam.started)
        }

    @Test
    fun `HOST-36 TR-05 removing the current track goes to the walk's target, never to a row that played`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.controller.setRepeat(true)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"))
            assertEquals(listOf("X", "b", "c", "a"), h.playOn(4))
            h.engine.commands.clear()
            h.controller.removeIndices(setOf(0))
            assertEquals(listOf("pause", "remove 0", "seek 1"), h.engine.commands.withoutPauseAtEnd())
            assertEquals("b", h.currentId())
            assertEquals(base("b", next = "c").copy(paused = true), jam.playbacks.last())
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-36 D8 outside a jam removing the current track plays on with the walk's target only`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.controller.setRepeat(true)
            h.engine.startPlaying()
            h.startJam()
            h.state(item("i1", "X"))
            assertEquals(listOf("X"), h.playOn(1))
            h.state()
            h.controller.endJam()
            assertEquals(listOf("b", "c", "a"), h.playOn(3))
            h.engine.commands.clear()
            h.controller.removeIndices(setOf(0))
            assertEquals(listOf("remove 0", "seek 1"), h.engine.commands.withoutPauseAtEnd())
            assertEquals("b", h.currentId())
            assertTrue(h.engine.playing)
            runCurrent()
            assertEquals(listOf("a", "b", "c", "a", "b"), h.source.reports.map { it.first })
        }

    @Test
    fun `HOST-36 TR-04 removing the current track and the rest stops at the end, idle till an item comes`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.engine.seekTo(1)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.engine.commands.clear()
            h.controller.removeIndices(setOf(1, 2))
            assertEquals(listOf("a"), h.engine.ids())
            assertEquals(listOf("pause", "remove 2", "remove 1", "pause", "seek to 0"), h.engine.commands)
            assertFalse(h.engine.isEnded)
            assertEquals(idle, jam.playbacks.last())
            h.controller.next()
            assertEquals("a", h.currentId())
            assertEquals(idle, jam.playbacks.last())
            h.engine.commands.clear()
            h.state(item("i1", "X"))
            assertEquals(listOf("insert 1 [X]", "seek 1", "prepare", "play"), h.engine.commands)
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-36 TR-04 outside a jam removing the last track stops at the end on the row before it`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.engine.startPlaying()
            h.playOn(2)
            h.engine.commands.clear()
            h.controller.removeIndices(setOf(2))
            assertEquals(listOf("a", "b"), h.engine.ids())
            assertEquals("b", h.currentId())
            assertEquals(
                listOf("remove 2", "seek 1", "pause", "seek to 0"),
                h.engine.commands.withoutPauseAtEnd(),
            )
            assertFalse(h.engine.isEnded)
            h.controller.next()
            assertEquals("b", h.currentId())
            runCurrent()
            assertEquals(listOf("a", "b", "c"), h.source.reports.map { it.first })
        }

    @Test
    fun `HOST-36 CONT-01 Clear keeps the current track and the jam part, the empty base takes the setting`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1")) }
            h.engine.seekTo(1)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"), item("i2", "Y"))
            val loaded = CompletableDeferred<List<Track>>()
            h.controller.loadSource("Mix") { loaded.await() }
            h.continueWithVibe = true
            h.engine.commands.clear()
            h.controller.clear()
            loaded.complete(tracks("m1"))
            runCurrent()
            assertEquals(listOf("b", "X", "Y"), h.engine.ids())
            assertEquals(listOf("remove 4", "remove 0"), h.engine.commands)
            assertEquals("", h.controller.state.value.title)
            assertTrue(h.controller.state.value.continueWithVibe)
            assertTrue(h.engine.playWhenReady)
            assertEquals(base("b", next = "X"), jam.playbacks.last())
            assertEquals(listOf("X", "Y"), h.playOn(2))
            runCurrent()
            assertEquals(listOf("startWave [track:X]"), h.source.calls)
            assertEquals(listOf("b", "X", "Y", "w1"), h.engine.ids())
        }

    @Test
    fun `HOST-32 HOST-36 an earlier jam's items are the base's, removed at once and dropped by Clear`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.engine.startPlaying()
            h.startJam()
            h.state(item("i1", "X"), item("i2", "Y"))
            h.controller.endJam()
            val jam = h.startJam()
            h.state(item("i3", "Z"))
            assertEquals(listOf("a", "Z", "X", "Y", "b"), h.engine.ids())
            h.controller.removeIndices(setOf(3))
            assertEquals(emptyList<List<String>>(), jam.removals)
            assertEquals(listOf("a", "Z", "X", "b"), h.engine.ids())
            h.controller.clear()
            assertEquals(listOf("a", "Z"), h.engine.ids())
            assertEquals(listOf("Z"), h.playOn(1))
            assertEquals(listOf("i3"), jam.started)
        }

    @Test
    fun `HOST-12 a jump to a base row moves the waiting items right behind it`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", "c", "d", continueWithVibe = false)
        h.engine.startPlaying()
        val jam = h.startJam()
        h.state(item("i1", "X"), item("i2", "Y"))
        h.engine.commands.clear()
        h.engine.seekTo(4)
        assertEquals(listOf("a", "b", "c", "X", "Y", "d"), h.engine.ids())
        assertEquals(
            listOf("seek 4", "remove 2", "remove 1", "insert 3 [X, Y]"),
            h.engine.commands.withoutPauseAtEnd(),
        )
        assertEquals(base("c", next = "X"), jam.playbacks.last())
        assertEquals(listOf("X", "Y", "d"), h.playOn(3))
        assertEquals(listOf("i1", "i2"), jam.started)
    }

    @Test
    fun `HOST-12 HOST-36 a tap on a base row while an item is held plays it, and the item waits behind it`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"))
            h.controller.removeIndices(setOf(0))
            assertEquals(listOf("X", "b", "c"), h.engine.ids())
            h.engine.seekTo(2)
            h.engine.startPlaying()
            assertEquals(listOf("b", "c", "X"), h.engine.ids())
            assertEquals("c", h.currentId())
            assertTrue(h.controller.state.value.entries[2].isWaiting)
            assertEquals(base("c", next = "X"), jam.playbacks.last())
            assertEquals(emptyList<String>(), jam.started)
            assertEquals(listOf("X"), h.playOn(1))
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-36 HOST-12 after the engine's own move the walk's target is held, items gather behind it`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"), item("i2", "Y"))
            h.engine.commands.clear()
            h.controller.removeIndices(setOf(0, 1))
            assertEquals(listOf(listOf("i1")), jam.removals)
            assertEquals(
                listOf("pause", "remove 0", "seek 1", "remove 0", "insert 1 [X]"),
                h.engine.commands.withoutPauseAtEnd(),
            )
            assertEquals(listOf("Y", "X", "b"), h.engine.ids())
            assertEquals("Y", h.currentId())
            assertEquals(idle, jam.playbacks.last())
            h.state(item("i2", "Y"))
            assertEquals(listOf("Y", "b"), h.engine.ids())
            h.engine.startPlaying()
            assertEquals(listOf("i2"), jam.started)
        }

    @Test
    fun `HOST-12 TR-03 shuffle switched on while an item waits leads the new pass with it, then the head`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", "d", continueWithVibe = false)
            h.engine.seekTo(1)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"))
            assertEquals(listOf("a", "b", "X", "c", "d"), h.engine.ids())
            h.controller.setShuffle(true)
            assertEquals(listOf(1, 2, 0, 3, 4), h.engine.playOrder())
            assertEquals(listOf("X", "a", "c", "d"), h.playOn(4))
            assertEquals(listOf("i1"), jam.started)
        }

    @Test
    fun `HOST-21 TR-03 with shuffle a pick leads the new pass with the waiting items, then the new head`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", continueWithVibe = false)
            h.controller.setShuffle(true)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.state(item("i1", "X"), item("i2", "Y"))
            h.controller.loadSource("Mix") { tracks("m1", "m2", "m3") }
            runCurrent()
            assertEquals(listOf("a", "X", "Y", "m1", "m2", "m3"), h.engine.ids())
            assertEquals(listOf(0, 1, 2, 3, 4, 5), h.engine.playOrder())
            assertEquals(listOf("X", "Y", "m1", "m2", "m3"), h.playOn(5))
            assertEquals(listOf("i1", "i2"), jam.started)
        }

    @Test
    fun `HOST-36 D8 TR-03 removing the current last row, shuffled first, lands on the target`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", "c", "d", continueWithVibe = false)
        h.engine.seekTo(3)
        h.controller.setShuffle(true)
        h.engine.startPlaying()
        assertEquals(listOf(3, 0, 1, 2), h.engine.playOrder())
        h.engine.commands.clear()
        h.controller.removeIndices(setOf(3))
        assertEquals(listOf("remove 3"), h.engine.commands.withoutPauseAtEnd())
        assertEquals("a", h.currentId())
        assertFalse(h.engine.isEnded)
        assertTrue(h.engine.playWhenReady)
        assertEquals(listOf("b", "c"), h.playOn(2))
    }

    @Test
    fun `HOST-36 TR-03 in a jam removing the current last row, shuffled first, reports the target`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", "d", continueWithVibe = false)
            h.engine.seekTo(3)
            h.controller.setShuffle(true)
            h.engine.startPlaying()
            val jam = h.startJam()
            h.engine.commands.clear()
            h.controller.removeIndices(setOf(3))
            assertEquals(listOf("pause", "remove 3"), h.engine.commands.withoutPauseAtEnd())
            assertEquals("a", h.currentId())
            assertFalse(h.engine.isEnded)
            assertEquals(base("a", next = "b").copy(paused = true), jam.playbacks.last())
            h.engine.startPlaying()
            assertEquals(base("a", next = "b"), jam.playbacks.last())
        }

    @Test
    fun `HOST-36 TR-04 removing the current middle row, shuffled last, stops on the new order's last row`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", "c", "d", continueWithVibe = false)
            h.engine.seekTo(3)
            h.controller.setShuffle(true)
            h.engine.startPlaying()
            assertEquals(listOf(3, 0, 1, 2), h.engine.playOrder())
            assertEquals(listOf("a", "b", "c"), h.playOn(3))
            h.engine.commands.clear()
            h.controller.removeIndices(setOf(2))
            assertEquals(listOf("a", "b", "d"), h.engine.ids())
            assertEquals(listOf(2, 0, 1), h.engine.playOrder())
            assertEquals(
                listOf("remove 2", "seek 1", "pause", "seek to 0"),
                h.engine.commands.withoutPauseAtEnd(),
            )
            assertEquals("b", h.currentId())
            assertFalse(h.engine.isEnded)
            assertFalse(h.engine.playWhenReady)
            assertFalse(h.engine.playing)
            h.controller.next()
            assertEquals("b", h.currentId())
            runCurrent()
            assertEquals(listOf("d", "a", "b", "c"), h.source.reports.map { it.first })
        }

    @Test
    fun `HOST-21 HOST-08 a pick into an empty player holds its first row, an item then plays first`() =
        runTest {
            val h = QueueHarness(this)
            val jam = h.startJam()
            h.controller.loadSource("Mix") { tracks("m1", "m2") }
            runCurrent()
            assertEquals(listOf("m1", "m2"), h.engine.ids())
            assertEquals(idle, jam.playbacks.last())
            h.engine.commands.clear()
            h.state(item("i1", "X"), item("i2", "Y"))
            assertEquals(listOf("X", "Y", "m1", "m2"), h.engine.ids())
            assertEquals(listOf("insert 0 [X, Y]", "seek 0"), h.engine.commands.withoutPauseAtEnd())
            assertEquals("X", h.currentId())
            assertFalse(h.engine.playWhenReady)
            assertEquals(idle, jam.playbacks.last())
            assertEquals(emptyList<String>(), jam.started)
            h.engine.startPlaying()
            assertEquals(listOf("i1"), jam.started)
            assertEquals(listOf("Y", "m1", "m2"), h.playOn(3))
            assertEquals(listOf("i1", "i2"), jam.started)
        }

    @Test
    fun `HOST-21 TR-03 with shuffle a pick into an empty player and items, Next plays them, then the pass`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.setShuffle(true)
            val jam = h.startJam()
            h.controller.loadSource("Mix") { tracks("m1", "m2", "m3") }
            runCurrent()
            h.state(item("i1", "X"), item("i2", "Y"))
            assertEquals(listOf("X", "Y", "m1", "m2", "m3"), h.engine.ids())
            assertEquals(listOf(0, 1, 2, 3, 4), h.engine.playOrder())
            h.engine.commands.clear()
            h.controller.next()
            assertEquals("X", h.currentId())
            assertEquals(listOf("prepare", "play"), h.engine.commands)
            assertEquals(listOf("i1"), jam.started)
            assertEquals(listOf("Y", "m1", "m2", "m3"), h.playOn(4))
        }

    @Test
    fun `HOST-21 a pick into an empty player is idle until Next, which prepares and plays its first row`() =
        runTest {
            val h = QueueHarness(this)
            val jam = h.startJam()
            h.controller.loadSource("Mix") { tracks("m1", "m2") }
            runCurrent()
            assertEquals(idle, jam.playbacks.last())
            h.engine.commands.clear()
            h.controller.next()
            assertEquals("m1", h.currentId())
            assertEquals(listOf("prepare", "play"), h.engine.commands)
            h.engine.startPlaying()
            assertEquals(base("m1", next = "m2"), jam.playbacks.last())
        }

    @Test
    fun `HOST-21 without a player a pick and an item, the player comes with the item held in front`() =
        runTest {
            val h = QueueHarness(this, attached = false)
            val jam = h.startJam()
            h.controller.loadSource("Mix") { tracks("m1") }
            runCurrent()
            h.state(item("i1", "X"))
            assertEquals(listOf("X", "m1"), h.controller.state.value.tracks.map { it.id })
            h.controller.attach(h.engine)
            assertEquals("X", h.currentId())
            assertFalse(h.engine.playWhenReady)
            assertEquals(idle, jam.playbacks.last())
            assertEquals(emptyList<String>(), jam.started)
            h.engine.startPlaying()
            assertEquals(listOf("i1"), jam.started)
            assertEquals(listOf("m1"), h.playOn(1))
        }

    @Test
    fun `HOST-21 a second pick into an empty player drops the held first one, Next plays its first row`() =
        runTest {
            for (attached in listOf(true, false)) {
                val h = QueueHarness(this, attached = attached)
                val jam = h.startJam()
                h.controller.loadSource("A") { tracks("a1", "a2") }
                runCurrent()
                h.controller.loadSource("B") { tracks("b1", "b2") }
                runCurrent()
                val entries = h.controller.state.value.entries
                assertEquals("$attached", listOf("b1", "b2"), entries.map { it.track.id })
                assertEquals("$attached", listOf(EntryPart.HEAD, EntryPart.HEAD), entries.map { it.part })
                if (!attached) h.controller.attach(h.engine)
                assertEquals("$attached", listOf("b1", "b2"), h.engine.ids())
                assertEquals("$attached", "b1", h.currentId())
                assertFalse("$attached", h.engine.playWhenReady)
                assertEquals("$attached", idle, jam.playbacks.last())
                h.engine.commands.clear()
                h.controller.next()
                assertEquals("$attached", "b1", h.currentId())
                assertEquals("$attached", listOf("prepare", "play"), h.engine.commands)
                h.engine.startPlaying()
                assertEquals("$attached", base("b1", next = "b2"), jam.playbacks.last())
                assertEquals("$attached", listOf("b2"), h.playOn(1))
            }
        }

    @Test
    fun `HOST-36 HOST-21 Clear after a pick into an empty player leaves it empty, Next plays nothing`() =
        runTest {
            for (attached in listOf(true, false)) {
                val h = QueueHarness(this, attached = attached)
                val jam = h.startJam()
                h.controller.loadSource("A") { tracks("a1", "a2") }
                runCurrent()
                h.controller.clear()
                assertEquals("$attached", emptyList<QueueEntry>(), h.controller.state.value.entries)
                assertEquals("$attached", idle, jam.playbacks.last())
                if (!attached) h.controller.attach(h.engine)
                assertEquals("$attached", emptyList<String>(), h.engine.ids())
                h.controller.next()
                assertEquals("$attached", emptyList<String>(), h.engine.ids())
                assertFalse("$attached", h.engine.playWhenReady)
                assertEquals("$attached", idle, jam.playbacks.last())
            }
        }

    @Test
    fun `HOST-21 TR-04 a pick while the end waits for the vibe stops there if paused, else plays on`() =
        runTest {
            for (playing in listOf(false, true)) {
                val h = QueueHarness.likes(this, "a", "b", continueWithVibe = true)
                h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
                h.engine.seekTo(1)
                h.engine.startPlaying()
                if (!playing) h.engine.pause()
                val jam = h.startJam()
                h.controller.next()
                assertEquals("$playing", idle, jam.playbacks.last())
                h.controller.loadSource("Mix") { tracks("m1") }
                runCurrent()
                assertEquals("$playing", listOf("b", "m1"), h.engine.ids())
                if (playing) {
                    assertEquals(base("b", next = "m1"), jam.playbacks.last())
                    assertEquals(listOf("m1"), h.playOn(1))
                } else {
                    assertEquals(idle, jam.playbacks.last())
                    h.engine.commands.clear()
                    h.controller.next()
                    assertEquals("m1", h.currentId())
                    assertEquals(listOf("next", "prepare", "play"), h.engine.commands.withoutPauseAtEnd())
                }
            }
        }

    @Test
    fun `HOST-36 HOST-11 after Clear the track that ends goes on with the jam vibe of the seeds`() = runTest {
        val h = QueueHarness.likes(this, "a", "b", continueWithVibe = false)
        h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1")) }
        h.engine.startPlaying()
        h.startJam()
        h.state(seeds = listOf("genre:jam"), seedsVersion = 1)
        h.continueWithVibe = true
        h.controller.clear()
        runCurrent()
        assertEquals(emptyList<String>(), h.source.calls)
        h.engine.finishTrack()
        runCurrent()
        assertEquals("startWave [genre:jam]", h.source.calls.first())
        assertEquals(listOf("a", "w1"), h.engine.ids())
        assertEquals("w1", h.currentId())
        assertTrue(h.engine.playWhenReady)
    }

    @Test
    fun `HOST-36 CONT-10 removing every head row ends the repeat, the jam vibe asks behind the item`() =
        runTest {
            val h = QueueHarness.likes(this, "a", "b", continueWithVibe = true)
            h.source.onStartWave = { WaveBatch("SJ", "B1", tracks("w1", "w2")) }
            h.controller.setRepeat(true)
            h.engine.startPlaying()
            h.startJam()
            h.state(item("i1", "X"), seeds = listOf("genre:jam"), seedsVersion = 1)
            runCurrent()
            assertEquals(emptyList<String>(), h.source.calls)
            assertTrue(h.engine.repeatEnabled)
            h.controller.removeIndices(setOf(0, 2))
            runCurrent()
            assertFalse(h.engine.repeatEnabled)
            assertEquals(listOf("startWave [genre:jam]"), h.source.calls)
            assertEquals(listOf("X", "w1", "w2"), h.engine.ids())
            h.engine.startPlaying()
            assertEquals(listOf("w1", "w2"), h.playOn(2))
        }

    private class Recorder : JamPlaybackListener {
        val started = mutableListOf<String>()
        val playbacks = mutableListOf<JamPlayback>()
        val removals = mutableListOf<List<String>>()

        override fun onItemStarted(itemId: String) {
            started += itemId
        }

        override fun onPlayback(playback: JamPlayback) {
            playbacks += playback
        }

        override fun onRemoveItems(itemIds: List<String>) {
            removals += itemIds
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

    private fun List<String>.withoutPauseAtEnd(): List<String> = filterNot { it.startsWith("pause at end") }

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

    private fun QueueHarness.startJam(waveFeedback: Boolean = false, afterRestart: Boolean = false) =
        Recorder().also { controller.startJam(it, waveFeedback, afterRestart) }

    private fun TestScope.restart(first: QueueHarness) =
        QueueHarness(this, store = FakeQueueStore(first.store.text), continueWithVibe = true)

    private fun QueueHarness.state(
        vararg entries: JamEntry,
        seeds: List<String> = emptyList(),
        seedsVersion: Int = 0,
    ) = controller.onJamQueue(entries.toList(), seeds, seedsVersion)
}
