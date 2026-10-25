package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.FakeContinuation
import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.WaveBatch
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueSpotifyTest {
    @Test
    fun `SPOT-19 CONT-05 a Spotify track sends no play-audio and no feedback, and never becomes a seed`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            h.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            h.controller.setQueue(
                listOf(spotify(SPOTIFY_ID), yandex("1")),
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
            assertTrue(h.source.calls.none { SPOTIFY_ID in it })
        }

    @Test
    fun `SPOT-19 no rotor feedback for a Spotify track, even from a row with a wave context`() = runTest {
        val wave =
            QueueSnapshot(
                tracks = listOf(spotify(SPOTIFY_ID), yandex("2")),
                batchIds = listOf("B1", "B1"),
                title = "My Wave",
                sourceId = "user:onyourwave",
                isWave = true,
                waveSessionId = "S1",
                waveStationId = "user:onyourwave",
                index = 0,
                positionMs = 0,
                shuffle = false,
                repeat = false,
            )
        val h = QueueHarness(this, store = FakeQueueStore(QueueSnapshotCodec.encode(wave)))
        h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        h.engine.startPlaying()
        h.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("trackStarted S1/user:onyourwave/B1 2 0.0"), h.source.feedback)
        assertEquals(listOf("2" to "play-1"), h.source.reports)
        assertTrue(h.source.calls.toString(), h.source.calls.none { SPOTIFY_ID in it })
    }

    @Test
    fun `SPOT-19 a Spotify track is never liked or disliked, a dislike of Yandex's 42 skips no Spotify 42`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Spotify") { listOf(spotify("42"), spotify("43")) }
            runCurrent()
            h.controller.setLiked(spotify("42"), true)
            h.controller.toggleLike(spotify("42"))
            h.controller.likeCurrent()
            h.controller.dislikeCurrent()
            h.controller.dislike(spotify("42"))
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
    fun `CONT-04 WAVE-05 the wave from history asks for more with the Yandex tracks of the queue only`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            val more = CompletableDeferred<WaveBatch>()
            h.source.onStartWave = { WaveBatch("V1", "B1", QueueHarness.tracks("v1", "v2")) }
            h.source.onMoreWave = { _, _ -> more.await() }
            h.controller.loadSource("Mix") { listOf(yandex("a"), spotify(SPOTIFY_ID), yandex("b")) }
            runCurrent()
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            repeat(3) { h.engine.finishTrack() }
            runCurrent()
            assertEquals(listOf("startWave [track:a]", "moreWave V1 [a, b, v1, v2]"), h.source.calls)
        }

    @Test
    fun `QUAL-05 a Spotify next track is never checked against Yandex's links or loaded again`() = runTest {
        val h = QueueHarness(this)
        h.controller.loadSource("Spotify") { listOf(spotify("1"), spotify("2")) }
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
    fun `SPOT-20 CONT-01 under the flag autoplay follows the head with the started tracks, oldest first`() =
        runTest {
            val autoplay = FakeContinuation()
            val h = pick(this, "s1", "s2", "s3", "s4", autoplay = autoplay)
            autoplay.replies(listOf(spotify("x1"), spotify("x", available = false), spotify("x2")))
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(emptyList<List<String>>(), autoplay.asked)
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf(listOf("s1", "s2", "s3")), autoplay.asked)
            assertEquals(listOf("s1", "s2", "s3", "s4", "x1", "x2"), h.engine.ids())
            assertEquals(
                List(4) { EntryPart.HEAD } + List(2) { EntryPart.CONTINUATION },
                h.controller.state.value.entries.map { it.part },
            )
            assertFalse("no footer for the silent request", h.controller.state.value.loadingMore)
            assertEquals(emptyList<QueueEvent>(), h.events)
            assertEquals(emptyList<String>(), h.source.calls)
            assertEquals(emptyList<String>(), h.source.feedback)
        }

    @Test
    fun `SPOT-20 WAVE-05 WAVE-06 the next page comes when two are left again, one request at a time`() =
        runTest {
            val autoplay = FakeContinuation()
            val h = pick(this, "s1", "s2", "s3", autoplay = autoplay)
            val second = CompletableDeferred<List<Track>>()
            autoplay.replies(listOf(spotify("x1"), spotify("x2")), then = second)
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf(listOf("s1", "s2"), listOf("s1", "s2", "s3")), autoplay.asked)
            h.controller.next()
            runCurrent()
            assertEquals(2, autoplay.asked.size)
            second.complete(listOf(spotify("x3")))
            runCurrent()
            assertEquals(listOf("s1", "s2", "s3", "x1", "x2", "x3"), h.engine.ids())
        }

    @Test
    fun `SPOT-20 WAVE-08 the head ends before the reply, and playback waits for it`() = runTest {
        val autoplay = FakeContinuation()
        val reply = CompletableDeferred<List<Track>>()
        autoplay.replies(then = reply)
        val h = pick(this, "s1", "s2", autoplay = autoplay)
        h.engine.startPlaying()
        h.engine.finishTrack()
        h.engine.finishTrack()
        runCurrent()
        assertTrue(h.engine.isEnded)
        assertTrue(h.controller.state.value.loadingMore)
        assertEquals(listOf(emptyList<String>()), autoplay.asked)
        reply.complete(listOf(spotify("x1")))
        runCurrent()
        assertEquals(listOf("append [x1]", "seek 2", "prepare", "play"), h.engine.commands.takeLast(4))
        assertEquals(2, h.engine.currentIndex)
        assertFalse(h.controller.state.value.loadingMore)
    }

    @Test
    fun `SPOT-20 CONT-02 with the flag off nothing is asked and the head stops, switched on it asks`() =
        runTest {
            val autoplay = FakeContinuation()
            val h = pick(this, "s1", "s2", "s3", flag = false, autoplay = autoplay)
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.engine.isEnded)
            assertEquals(emptyList<List<String>>(), autoplay.asked)
            assertTrue(h.controller.state.value.waveFromHistory)
            autoplay.replies(listOf(spotify("x1")))
            h.controller.setContinueWithVibe(true)
            runCurrent()
            assertEquals(listOf(listOf("s1", "s2", "s3")), autoplay.asked)
            assertEquals(listOf("s1", "s2", "s3", "x1"), h.engine.ids())
            h.controller.next()
            assertEquals(3, h.engine.currentIndex)
        }

    @Test
    fun `SPOT-20 WAVE-07 an autoplay reply for a replaced queue is dropped`() = runTest {
        val autoplay = FakeContinuation()
        val reply = CompletableDeferred<List<Track>>()
        autoplay.replies(then = reply)
        val h = pick(this, "s1", "s2", autoplay = autoplay)
        assertEquals(1, autoplay.asked.size)
        h.controller.loadSource("Road") { listOf(spotify("r1"), spotify("r2"), spotify("r3")) }
        runCurrent()
        reply.complete(listOf(spotify("x1")))
        runCurrent()
        assertEquals(listOf("r1", "r2", "r3"), h.engine.ids())
        assertEquals(listOf(QueueEvent.SourceLoaded("Road", 3)), h.events)
    }

    @Test
    fun `SPOT-20 the started tracks have no repeats, oldest first, at most 50`() = runTest {
        val autoplay = FakeContinuation()
        val h = pick(this, "s1", "s2", "s3", "s4", "s5", autoplay = autoplay)
        autoplay.onMore = { CompletableDeferred<List<Track>>().await() }
        h.engine.startPlaying()
        h.engine.finishTrack()
        h.engine.seekTo(0)
        h.engine.seekTo(3)
        runCurrent()
        assertEquals(listOf(listOf("s2", "s1", "s4")), autoplay.asked)
        val started = StartedTracks((1..60).map { spotify("t$it") } + spotify("t30"))
        assertEquals((11..60).map { "t$it" }.minus("t30") + "t30", started.tracks.map(Track::id))
        assertEquals(StartedTracks.MAX, started.tracks.size)
    }

    @Test
    fun `SPOT-22 the 50 started tracks are counted per source, other sources do not crowd out Spotify`() {
        val started = StartedTracks()
        (1..40).forEach { started.note(spotify("s$it")) }
        (1..60).forEach { started.note(yandex("y$it")) }
        assertEquals((1..40).map { "s$it" }, idsOf(started, TrackSource.SPOTIFY))
        assertEquals((11..60).map { "y$it" }, idsOf(started, TrackSource.YANDEX))
        (41..60).forEach { started.note(spotify("s$it")) }
        assertEquals((11..60).map { "s$it" }, idsOf(started, TrackSource.SPOTIFY))
    }

    @Test
    fun `SPOT-20 WAVE-09 a later autoplay page failure does not stall, the next change asks`() = runTest {
        val autoplay = FakeContinuation()
        var call = 0
        autoplay.onMore = {
            call++
            when (call) {
                1 -> listOf(spotify("x1"), spotify("x2"), spotify("x3"))
                2 -> throw IOException("offline")
                else -> CompletableDeferred<List<Track>>().await()
            }
        }
        val h = pick(this, "s1", "s2", "s3", autoplay = autoplay)
        h.engine.startPlaying()
        repeat(5) {
            h.engine.finishTrack()
            runCurrent()
        }
        assertEquals(3, autoplay.asked.size)
    }

    @Test
    fun `SPOT-21 a failed autoplay reply for a replaced queue shows nothing`() = runTest {
        val autoplay = FakeContinuation()
        val reply = CompletableDeferred<List<Track>>()
        autoplay.replies(then = reply)
        val h = pick(this, "s1", "s2", autoplay = autoplay)
        assertEquals(1, autoplay.asked.size)
        h.controller.loadSource("Road") { listOf(spotify("r1"), spotify("r2"), spotify("r3")) }
        runCurrent()
        reply.completeExceptionally(IOException("late"))
        runCurrent()
        assertEquals(listOf(QueueEvent.SourceLoaded("Road", 3)), h.events)
    }

    @Test
    fun `SPOT-12 a radio after a Yandex wave has no wave seed and no wave session in the file`() = runTest {
        val h = QueueHarness(this)
        h.source.onStartWave = { WaveBatch("S1", "B1", QueueHarness.tracks("w1", "w2", "w3")) }
        h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        h.controller.playWave(listOf("genre:rock"), "Rock")
        runCurrent()
        val radio = FakeContinuation()
        radio.replies(listOf(spotify("r1"), spotify("r2"), spotify("r3")))
        h.controller.playContinuation("Song: radio", "spotify:radio:r", radio)
        runCurrent()
        assertNull(h.controller.currentWaveSeed())
        assertEquals("", h.store.snapshot()!!.waveSessionId)
    }

    @Test
    fun `SPOT-20 a new Spotify pick does not send the old pick's started tracks`() = runTest {
        val first = FakeContinuation()
        first.onMore = { CompletableDeferred<List<Track>>().await() }
        val h = pick(this, "s1", "s2", "s3", "s4", "s5", autoplay = first)
        h.engine.startPlaying()
        h.engine.finishTrack()
        runCurrent()
        val second = FakeContinuation()
        second.onMore = { CompletableDeferred<List<Track>>().await() }
        h.controller.loadSource("Other", continuation = second) { listOf(spotify("t1"), spotify("t2")) }
        runCurrent()
        h.engine.startPlaying()
        h.engine.finishTrack()
        runCurrent()
        assertTrue(second.asked.isNotEmpty())
        assertTrue(second.asked.flatten().none { it.startsWith("s") })
    }

    @Test
    fun `SPOT-21 CONT-07 a failed autoplay shows its error, appends nothing, the head stops at its end`() =
        runTest {
            val autoplay = FakeContinuation()
            autoplay.onMore = { throw IOException("offline") }
            val h = pick(this, "s1", "s2", "s3", autoplay = autoplay, kind = ErrorKind.SpotifyLoggedOut)
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf(QueueEvent.Failed(Stage.AUTOPLAY, ErrorKind.SpotifyLoggedOut)), h.events)
            assertEquals(listOf("AUTOPLAY offline"), h.logged)
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.engine.isEnded)
            assertEquals(1, autoplay.asked.size)
            assertEquals(listOf("s1", "s2", "s3"), h.engine.ids())
        }

    @Test
    fun `SPOT-21 CONT-07 an autoplay with no available track shows nothing, and the head stops at its end`() =
        runTest {
            val autoplay = FakeContinuation()
            autoplay.replies(listOf(spotify("x", available = false)))
            val h = pick(this, "s1", "s2", "s3", autoplay = autoplay)
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.engine.isEnded)
            assertEquals(emptyList<QueueEvent>(), h.events)
            assertEquals(1, autoplay.asked.size)
        }

    @Test
    fun `SPOT-21 CONT-07 the next Next at the end asks again with the started tracks of then, and plays`() =
        runTest {
            val autoplay = FakeContinuation()
            autoplay.onMore = { throw IOException("offline") }
            val h = pick(this, "s1", "s2", "s3", autoplay = autoplay)
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            autoplay.replies(listOf(spotify("x1")))
            h.engine.commands.clear()
            h.controller.next()
            runCurrent()
            assertEquals(QueueEvent.LoadingMore, h.events.last())
            assertEquals(listOf(listOf("s1", "s2"), listOf("s1", "s2", "s3")), autoplay.asked.take(2))
            assertEquals(listOf("append [x1]", "seek 3", "prepare", "play"), h.engine.commands)
            assertEquals(3, h.engine.currentIndex)
        }

    @Test
    fun `ERR-07 SPOT-20 a broken last head track waits for the autoplay and plays its first track`() =
        runTest {
            val autoplay = FakeContinuation()
            val reply = CompletableDeferred<List<Track>>()
            autoplay.replies(then = reply)
            val h = pick(this, "s1", "s2", "s3", autoplay = autoplay)
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.finishTrack()
            h.engine.fail(FailureKind.TRACK)
            reply.complete(listOf(spotify("x1")))
            runCurrent()
            assertEquals(3, h.engine.currentIndex)
            assertEquals(listOf("append [x1]", "seek 3", "prepare", "play"), h.engine.commands.takeLast(4))
        }

    @Test
    fun `CONT-09 SPOT-20 with shuffle the head plays a pass, then the autoplay tracks in their order`() =
        runTest {
            val autoplay = FakeContinuation()
            autoplay.replies(listOf(spotify("x1"), spotify("x2")))
            val h = pick(this, "s1", "s2", "s3", "s4", autoplay = autoplay)
            h.controller.setShuffle(true)
            h.engine.shuffleOrder = listOf(0, 3, 1, 2)
            h.engine.startPlaying()
            val played = (1..5).map {
                h.engine.finishTrack()
                runCurrent()
                h.engine.tracks[h.engine.currentIndex].id
            }
            assertEquals(listOf("s4", "s2", "s3", "x1", "x2"), played)
            assertEquals(listOf(listOf("s1", "s4", "s2"), listOf("s1", "s4", "s2", "s3")), autoplay.asked)
            assertEquals(listOf(0, 3, 1, 2, 4, 5), h.engine.playOrder())
        }

    @Test
    fun `CONT-10 SPOT-20 repeat wraps the head, and autoplay is not asked`() = runTest {
        val autoplay = FakeContinuation()
        val h = pick(this, "s1", "s2", "s3", autoplay = autoplay)
        h.controller.setRepeat(true)
        h.engine.startPlaying()
        repeat(7) { h.engine.finishTrack() }
        h.controller.next()
        runCurrent()
        assertEquals(emptyList<List<String>>(), autoplay.asked)
        assertEquals(listOf("s1", "s2", "s3"), h.engine.ids())
        assertEquals(2, h.engine.currentIndex)
        assertTrue(h.engine.repeatEnabled)
    }

    @Test
    fun `CONT-11 SPOT-20 on autoplay tracks shuffle does not reorder, repeat does not wrap, the end waits`() =
        runTest {
            val autoplay = FakeContinuation()
            val more = CompletableDeferred<List<Track>>()
            autoplay.replies(listOf(spotify("x1"), spotify("x2"), spotify("x3")), then = more)
            val h = pick(this, "s1", "s2", autoplay = autoplay)
            h.controller.setShuffle(true)
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.finishTrack()
            h.controller.setRepeat(true)
            assertTrue(h.engine.shuffleEnabled)
            assertFalse(h.engine.repeatEnabled)
            assertFalse(h.engine.pauseAtEndOfCurrent)
            assertEquals(listOf(0, 1, 2, 3, 4), h.engine.playOrder())
            h.controller.next()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.engine.isEnded)
            assertEquals(4, h.engine.currentIndex)
            assertEquals(listOf(emptyList(), listOf("s1", "s2")), autoplay.asked)
            more.complete(listOf(spotify("m1")))
            runCurrent()
            assertEquals(5, h.engine.currentIndex)
            assertEquals(listOf("append [m1]", "seek 5", "prepare", "play"), h.engine.commands.takeLast(4))
        }

    @Test
    fun `SPOT-12 WAVE-01 a radio says loading, its first tracks replace the queue and play, more at 2`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Liked") { listOf(yandex("a")) }
            runCurrent()
            h.events.clear()
            val radio = FakeContinuation(ContinuationKey("fake-station", "spotify:track:r"))
            val first = CompletableDeferred<List<Track>>()
            val more = CompletableDeferred<List<Track>>()
            val pages = ArrayDeque(listOf(first, more))
            radio.onMore = { (pages.removeFirstOrNull() ?: CompletableDeferred()).await() }
            h.controller.playContinuation("Song: radio", "spotify:radio:r", radio)
            runCurrent()
            assertEquals(listOf(QueueEvent.SourceLoading("Song: radio")), h.events)
            assertEquals(listOf("a"), h.engine.ids())
            first.complete(listOf(spotify("r1"), spotify("r2"), spotify("r3")))
            runCurrent()
            assertEquals("set [r1, r2, r3] play=true", h.engine.commands.last { it.startsWith("set") })
            val state = h.controller.state.value
            assertTrue(state.isWave)
            assertEquals("Song: radio", state.title)
            assertEquals("spotify:radio:r", state.activeSourceId)
            assertEquals(List(3) { EntryPart.CONTINUATION }, state.entries.map { it.part })
            assertTrue(h.controller.modes.value.locked)
            assertNull(h.controller.currentWaveSeed())
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf(emptyList<String>(), emptyList()), radio.asked)
            assertTrue("a station's footer shows its request", h.controller.state.value.loadingMore)
            more.complete(listOf(spotify("r4")))
            runCurrent()
            assertEquals(listOf("r1", "r2", "r3", "r4"), h.engine.ids())
            assertEquals(listOf(QueueEvent.SourceLoading("Song: radio")), h.events)
            assertEquals(emptyList<String>(), h.source.calls)
        }

    @Test
    fun `SPOT-12 WAVE-03 a radio with no available track says empty, and the queue stays`() = runTest {
        val h = QueueHarness(this)
        h.controller.loadSource("Liked") { listOf(yandex("a"), yandex("b")) }
        runCurrent()
        h.events.clear()
        val radio = FakeContinuation()
        radio.replies(listOf(spotify("r1", available = false)))
        h.controller.playContinuation("Song: radio", "spotify:radio:r", radio)
        runCurrent()
        assertEquals(
            listOf(QueueEvent.SourceLoading("Song: radio"), QueueEvent.SourceEmpty("Song: radio")),
            h.events,
        )
        assertEquals(listOf("a", "b"), h.engine.ids())
        assertFalse(h.controller.state.value.isWave)
    }

    @Test
    fun `SPOT-12 WAVE-04 a failed radio shows the classifier's error, and the queue stays`() = runTest {
        val h = QueueHarness(this, errorKindOf = { ErrorKind.ServerError(503) })
        h.controller.loadSource("Liked") { listOf(yandex("a")) }
        runCurrent()
        h.events.clear()
        val radio = FakeContinuation()
        radio.onMore = { throw IOException("HTTP 503") }
        h.controller.playContinuation("Song: radio", "spotify:radio:r", radio)
        runCurrent()
        assertEquals(QueueEvent.Failed(Stage.WAVE, ErrorKind.ServerError(503)), h.events.last())
        assertEquals(listOf("a"), h.engine.ids())
        assertEquals("Liked", h.controller.state.value.title)
    }

    @Test
    fun `SPOT-12 CONT-01 WAVE-10 WAVE-11 a radio ignores the flag, locks the modes, then gives them back`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = false)
            h.controller.setShuffle(true)
            val radio = FakeContinuation()
            radio.replies(listOf(spotify("r1"), spotify("r2")), listOf(spotify("r3")))
            h.controller.playContinuation("Song: radio", "spotify:radio:r", radio)
            runCurrent()
            assertFalse(h.engine.shuffleEnabled)
            h.controller.setRepeat(true)
            assertFalse(h.controller.modes.value.repeat)
            h.controller.setContinueWithVibe(true)
            assertFalse(h.controller.state.value.continueWithVibe)
            runCurrent()
            assertEquals(listOf("r1", "r2", "r3"), h.engine.ids())
            h.controller.loadSource("Road") { listOf(spotify("s1"), spotify("s2"), spotify("s3")) }
            runCurrent()
            assertTrue(h.engine.shuffleEnabled)
        }

    @Test
    fun `SPOT-12 WAVE-09 WAVE-12 a radio stopped at its end after a failed load-more asks again on Next`() =
        runTest {
            val h = QueueHarness(this)
            val radio = FakeContinuation()
            radio.replies(listOf(spotify("r1"), spotify("r2"), spotify("r3")))
            h.controller.playContinuation("Song: radio", "spotify:radio:r", radio)
            runCurrent()
            radio.onMore = { throw IOException("offline") }
            h.engine.startPlaying()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(Stage.WAVE_MORE, (h.events.last() as QueueEvent.Failed).stage)
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertTrue(h.engine.isEnded)
            assertEquals(3, radio.asked.size)
            runCurrent()
            assertEquals(3, radio.asked.size)
            val again = CompletableDeferred<List<Track>>()
            radio.replies(then = again)
            h.controller.next()
            runCurrent()
            assertEquals(QueueEvent.LoadingMore, h.events.last())
            assertEquals(4, radio.asked.size)
            again.complete(listOf(spotify("r4")))
            runCurrent()
            assertEquals(3, h.engine.currentIndex)
            assertEquals(listOf("append [r4]", "seek 3", "prepare", "play"), h.engine.commands.takeLast(4))
        }

    @Test
    fun `SPOT-11 a search says it started, then plays the best result under its name with its autoplay`() =
        runTest {
            val h = QueueHarness(this, continueWithVibe = true)
            val autoplay = FakeContinuation()
            autoplay.replies(listOf(spotify("x1")))
            val asked = ArrayList<String>()
            val reply = CompletableDeferred<SearchPick>()
            h.controller.search("кино", "Search: кино") { text ->
                asked += text
                reply.await()
            }
            runCurrent()
            assertEquals(listOf(QueueEvent.SearchStarted("Search: кино")), h.events)
            val found = listOf(spotify("a1"), spotify("u", available = false), spotify("a2"))
            reply.complete(SearchPick(found, "Кино", autoplay))
            runCurrent()
            assertEquals(listOf("кино"), asked)
            assertEquals(QueueEvent.SourceLoaded("Кино", 2), h.events.last())
            assertEquals("Кино", h.controller.state.value.title)
            assertNull(h.controller.state.value.activeSourceId)
            assertEquals("set [a1, a2] play=true", h.engine.commands.last { it.startsWith("set") })
            assertEquals(listOf("a1", "a2", "x1"), h.engine.ids())
            assertEquals(emptyList<String>(), h.source.calls)
        }

    @Test
    fun `SPOT-11 found tracks play under the search's title`() = runTest {
        val h = QueueHarness(this)
        h.controller.search("кино", "Search: кино") { SearchPick(listOf(spotify("a1"))) }
        runCurrent()
        assertEquals(QueueEvent.SourceLoaded("Search: кино", 1), h.events.last())
        assertEquals("Search: кино", h.controller.state.value.title)
    }

    @Test
    fun `SPOT-11 SRC-12 a search with no available track says Nothing found, and the queue stays`() =
        runTest {
            val h = QueueHarness(this)
            h.controller.loadSource("Liked") { listOf(yandex("a")) }
            runCurrent()
            h.events.clear()
            h.controller.search("кино", "Search: кино") {
                SearchPick(listOf(spotify("u", available = false)), "Кино", FakeContinuation())
            }
            runCurrent()
            assertEquals(listOf(QueueEvent.SearchStarted("Search: кино"), QueueEvent.NothingFound), h.events)
            assertEquals(listOf("a"), h.engine.ids())
        }

    @Test
    fun `SPOT-11 SRC-01 a failed search shows the classifier's search error, an older one says nothing`() =
        runTest {
            val h = QueueHarness(this, errorKindOf = { ErrorKind.Malformed })
            val older = CompletableDeferred<SearchPick>()
            h.controller.search("old", "Search: old") { older.await() }
            h.controller.search("кино", "Search: кино") { throw IOException("unreadable") }
            runCurrent()
            older.completeExceptionally(IOException("late"))
            runCurrent()
            assertEquals(
                listOf(
                    QueueEvent.SearchStarted("Search: old"),
                    QueueEvent.SearchStarted("Search: кино"),
                    QueueEvent.Failed(Stage.SEARCH, ErrorKind.Malformed),
                ),
                h.events,
            )
            assertEquals(listOf("SEARCH unreadable"), h.logged)
        }

    @Test
    fun `SRC-04 a failed Spotify pick shows the classifier's kind`() = runTest {
        val h = QueueHarness(this, errorKindOf = { ErrorKind.SpotifyLoggedOut })
        h.controller.loadSource("Liked Songs", continuation = FakeContinuation()) {
            throw IOException("no login")
        }
        runCurrent()
        assertEquals(listOf(QueueEvent.Failed(Stage.SOURCE, ErrorKind.SpotifyLoggedOut)), h.events)
    }

    @Test
    fun `SPOT-06 a Yandex logout's clear drops the Spotify queue with its autoplay, a reply in flight too`() =
        runTest {
            val autoplay = FakeContinuation()
            val reply = CompletableDeferred<List<Track>>()
            autoplay.replies(then = reply)
            val h = pick(this, "s1", "s2", autoplay = autoplay)
            h.controller.clear()
            reply.complete(listOf(spotify("x1")))
            runCurrent()
            assertEquals(emptyList<Track>(), h.controller.state.value.tracks)
            assertEquals(emptyList<String>(), h.engine.ids())
            val saved = h.store.snapshot()!!
            assertNull(saved.continuation)
            assertEquals(emptyList<Any>(), saved.started)
        }

    private fun pick(
        scope: TestScope,
        vararg ids: String,
        flag: Boolean = true,
        autoplay: FakeContinuation,
        kind: ErrorKind? = null,
    ): QueueHarness {
        val h =
            if (kind == null) {
                QueueHarness(scope, continueWithVibe = flag)
            } else {
                QueueHarness(scope, continueWithVibe = flag, errorKindOf = { kind })
            }
        h.controller.loadSource("Liked Songs", sourceId = "spotify:liked", continuation = autoplay) {
            ids.map { spotify(it) }
        }
        scope.runCurrent()
        h.events.clear()
        h.engine.commands.clear()
        return h
    }

    private fun yandex(id: String) = Track(id = id, title = "T$id")

    private fun idsOf(started: StartedTracks, source: TrackSource) =
        started.tracks.filter { it.source == source }.map(Track::id)

    private fun spotify(id: String, available: Boolean = true) =
        Track(id = id, title = "Spotify $id", available = available, source = TrackSource.SPOTIFY)

    private companion object {
        const val SPOTIFY_ID = "6PCoZdjSPbRMehHviTrpB3"
    }
}
