package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.MalformedResponseException
import io.github.kickoman.qiyaa.yandex.NetworkException
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
class QueueContinuationTest {
    @Test
    fun `CONT-01 a new queue takes the flag from the setting, on or off`() = runTest {
        val harness = QueueHarness(this, continueWithVibe = true)
        harness.controller.loadSource("Liked") { tracks("a", "b") }
        runCurrent()
        assertTrue(harness.controller.state.value.continueWithVibe)
        harness.continueWithVibe = false
        harness.controller.loadSource("Album") { tracks("c") }
        runCurrent()
        assertFalse(harness.controller.state.value.continueWithVibe)
        harness.continueWithVibe = true
        harness.controller.setQueue(tracks("d"), "Search", isWave = false, autoplay = true, sourceId = null)
        assertTrue(harness.controller.state.value.continueWithVibe)
    }

    @Test
    fun `CONT-01 clear takes the flag from the setting too`() = runTest {
        val harness = head(this, "a", "b")
        harness.controller.setContinueWithVibe(false)
        harness.controller.clear()
        assertTrue(harness.controller.state.value.continueWithVibe)
    }

    @Test
    fun `CONT-01 the flag changes nothing for My Vibe and a station`() = runTest {
        for (setting in listOf(true, false)) {
            val harness = QueueHarness(this, continueWithVibe = setting)
            harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
            harness.source.onMoreWave = { _, _ -> WaveBatch("", "B2", tracks("m1")) }
            harness.controller.playWave(listOf("genre:rock"), "Rock")
            runCurrent()
            assertEquals(setting, harness.controller.state.value.continueWithVibe)
            harness.controller.setContinueWithVibe(!setting)
            assertEquals(setting, harness.controller.state.value.continueWithVibe)
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(
                "$setting",
                listOf("startWave [genre:rock]", "moreWave S1 [w1, w2, w3]"),
                harness.source.calls,
            )
            assertEquals("$setting", listOf("w1", "w2", "w3", "m1"), harness.engine.ids())
        }
    }

    @Test
    fun `CONT-02 switching the flag changes this queue only`() = runTest {
        val harness = head(this, "a", "b", "c")
        harness.controller.setContinueWithVibe(false)
        assertFalse(harness.controller.state.value.continueWithVibe)
        assertTrue(harness.continueWithVibe)
        harness.controller.loadSource("Album") { tracks("d") }
        runCurrent()
        assertTrue(harness.controller.state.value.continueWithVibe)
    }

    @Test
    fun `CONT-02 switched on with two left, the vibe starts at once`() = runTest {
        val harness = head(this, "a", "b", "c", flag = false)
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(emptyList<String>(), harness.source.calls)
        harness.controller.setContinueWithVibe(true)
        runCurrent()
        assertEquals(listOf("startWave [track:b, track:a]"), harness.source.calls)
        assertEquals(listOf("a", "b", "c", "v1", "v2"), harness.engine.ids())
    }

    @Test
    fun `CONT-02 switched off, no more asks, its tracks play, then stop as TR-04`() = runTest {
        val harness = head(this, "a", "b")
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2", "v3")) }
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("a", "b", "v1", "v2", "v3"), harness.engine.ids())
        harness.controller.setContinueWithVibe(false)
        repeat(4) { harness.engine.finishTrack() }
        runCurrent()
        assertEquals(4, harness.engine.currentIndex)
        assertEquals(listOf("a", "b", "v1", "v2", "v3"), harness.source.reports.map { it.first })
        harness.engine.finishTrack()
        runCurrent()
        assertTrue(harness.engine.isEnded)
        assertEquals(listOf("startWave [track:a]"), harness.source.calls)
        assertEquals(listOf("a", "b", "v1", "v2", "v3"), harness.engine.ids())
    }

    @Test
    fun `CONT-02 switching to the value the queue has neither clears a stall nor asks again`() = runTest {
        val harness = head(this, "a", "b", "c")
        harness.source.onStartWave = { throw IllegalStateException("offline") }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.controller.setContinueWithVibe(true)
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(1, harness.source.calls.size)
        harness.controller.setContinueWithVibe(false)
        harness.controller.setContinueWithVibe(true)
        runCurrent()
        assertEquals(
            listOf("startWave [track:b, track:a]", "startWave [track:c, track:b, track:a]"),
            harness.source.calls,
        )
    }

    @Test
    fun `CONT-03 stopped at the natural end, a reply before Next waits, and Next plays it`() = runTest {
        val harness = stoppedAtTheEnd(this, byNext = false)
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.engine.commands.clear()
        harness.controller.setContinueWithVibe(true)
        runCurrent()
        assertEquals(listOf("append [v1, v2]"), harness.engine.commands)
        assertTrue(harness.engine.isEnded)
        assertEquals(1, harness.engine.currentIndex)
        harness.controller.next()
        assertEquals(2, harness.engine.currentIndex)
        assertFalse(harness.engine.isEnded)
        assertTrue(harness.engine.playWhenReady)
    }

    @Test
    fun `CONT-03 stopped at the natural end, a reply after Next plays when it comes`() = runTest {
        val harness = stoppedAtTheEnd(this, byNext = false)
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.controller.setContinueWithVibe(true)
        runCurrent()
        assertFalse("no footer while nothing waits", harness.controller.state.value.loadingMore)
        harness.engine.commands.clear()
        harness.controller.next()
        runCurrent()
        assertEquals(QueueEvent.LoadingMore, harness.events.last())
        assertTrue(harness.controller.state.value.loadingMore)
        reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
        runCurrent()
        assertEquals(listOf("append [v1, v2]", "seek 2", "prepare", "play"), harness.engine.commands)
        assertTrue(harness.engine.playWhenReady)
        assertFalse(harness.controller.state.value.loadingMore)
    }

    @Test
    fun `CONT-03 stopped by Next at the end, a reply before Next waits, and Next plays it`() = runTest {
        val harness = stoppedAtTheEnd(this, byNext = true)
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.engine.commands.clear()
        harness.controller.setContinueWithVibe(true)
        runCurrent()
        assertEquals(listOf("append [v1, v2]"), harness.engine.commands)
        assertFalse(harness.engine.playWhenReady)
        harness.controller.next()
        assertEquals(listOf("append [v1, v2]", "next", "prepare", "play"), harness.engine.commands)
        assertEquals(2, harness.engine.currentIndex)
        assertTrue(harness.engine.playWhenReady)
    }

    @Test
    fun `CONT-03 stopped by Next at the end, a reply after Next plays when it comes`() = runTest {
        val harness = stoppedAtTheEnd(this, byNext = true)
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.controller.setContinueWithVibe(true)
        runCurrent()
        harness.engine.commands.clear()
        harness.controller.next()
        runCurrent()
        assertEquals(QueueEvent.LoadingMore, harness.events.last())
        reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
        runCurrent()
        assertEquals(listOf("append [v1, v2]", "seek 2", "prepare", "play"), harness.engine.commands)
        assertEquals(2, harness.engine.currentIndex)
        assertTrue(harness.engine.playWhenReady)
    }

    @Test
    fun `CONT-03 WAVE-08 a track picked while waiting for the vibe keeps playing when the reply comes`() =
        runTest {
            val harness = head(this, "a", "b")
            val reply = CompletableDeferred<WaveBatch>()
            harness.source.onStartWave = { reply.await() }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            harness.engine.finishTrack()
            runCurrent()
            assertTrue(harness.controller.state.value.loadingMore)
            harness.engine.seekTo(0)
            assertFalse("the footer goes with the wait", harness.controller.state.value.loadingMore)
            harness.engine.commands.clear()
            reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
            runCurrent()
            assertEquals(listOf("append [v1, v2]"), harness.engine.commands)
            assertEquals(0, harness.engine.currentIndex)
        }

    @Test
    fun `CONT-03 WAVE-08 Play at the end that replays the last track keeps it when the reply comes`() =
        runTest {
            val harness = head(this, "a", "b")
            val reply = CompletableDeferred<WaveBatch>()
            harness.source.onStartWave = { reply.await() }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            harness.engine.finishTrack()
            runCurrent()
            assertTrue(harness.controller.state.value.loadingMore)
            harness.engine.seekTo(1)
            assertFalse(harness.controller.state.value.loadingMore)
            harness.engine.commands.clear()
            reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
            runCurrent()
            assertEquals(listOf("append [v1, v2]"), harness.engine.commands)
            assertEquals(1, harness.engine.currentIndex)
        }

    @Test
    fun `CONT-03 WAVE-08 Stop while waiting for the vibe stays stopped when the reply comes`() = runTest {
        val harness = head(this, "a", "b")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.controller.next()
        runCurrent()
        assertEquals(QueueEvent.LoadingMore, harness.events.last())
        assertTrue(harness.controller.state.value.loadingMore)
        harness.controller.stop()
        assertFalse(harness.controller.state.value.loadingMore)
        harness.engine.commands.clear()
        reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
        runCurrent()
        assertEquals(listOf("append [v1, v2]"), harness.engine.commands)
        assertEquals(1, harness.engine.currentIndex)
        assertFalse(harness.engine.playWhenReady)
    }

    @Test
    fun `CONT-04 two from the end the vibe starts on the started tracks, after the head, with no status`() =
        runTest {
            val harness = head(this, "a", "b", "c", "d")
            val reply = CompletableDeferred<WaveBatch>()
            harness.source.onStartWave = { reply.await() }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(emptyList<String>(), harness.source.calls)
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("startWave [track:c, track:b, track:a]"), harness.source.calls)
            assertFalse("no footer for the silent request", harness.controller.state.value.loadingMore)
            val batch = listOf(track("v1"), track("x", available = false), track("v2"))
            reply.complete(WaveBatch("V1", "B1", batch))
            runCurrent()
            assertEquals(listOf("a", "b", "c", "d", "v1", "v2"), harness.engine.ids())
            assertEquals(
                List(4) { EntryPart.HEAD } + List(2) { EntryPart.CONTINUATION },
                harness.controller.state.value.entries.map { it.part },
            )
            assertEquals(2, harness.engine.currentIndex)
            assertEquals(emptyList<QueueEvent>(), harness.events)
        }

    @Test
    fun `CONT-04 TRK-03 radioStarted with the first batch comes before the first trackStarted`() = runTest {
        val harness = head(this, "a", "b")
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.engine.startPlaying()
        runCurrent()
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(
            listOf("radioStarted V1/track:a/B1 - 0.0", "trackStarted V1/track:a/B1 v1 0.0"),
            harness.source.feedback,
        )
    }

    @Test
    fun `CONT-04 CONT-06 a start with two left asks when becoming current could not`() = runTest {
        val harness = head(this, "a", "b")
        harness.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
        runCurrent()
        assertEquals(emptyList<String>(), harness.source.calls)
        harness.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("startWave [track:a]"), harness.source.calls)
    }

    @Test
    fun `CONT-04 WAVE-05 WAVE-06 then it loads more with the last five ids, one request at a time`() =
        runTest {
            val harness = head(this, "a", "b", "c")
            val more = CompletableDeferred<WaveBatch>()
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            harness.source.onMoreWave = { _, _ -> more.await() }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            repeat(3) { harness.engine.finishTrack() }
            runCurrent()
            assertEquals(
                listOf("startWave [track:b, track:a]", "moreWave V1 [a, b, c, v1, v2]"),
                harness.source.calls,
            )
            assertFalse(harness.controller.state.value.loadingMore)
            more.complete(WaveBatch("", "B2", tracks("m1")))
            runCurrent()
            assertEquals(listOf("a", "b", "c", "v1", "v2", "m1"), harness.engine.ids())
            harness.engine.finishTrack()
            runCurrent()
            assertEquals("trackStarted V1/track:b/B2 m1 0.0", harness.source.feedback.last())
        }

    @Test
    fun `CONT-04 WAVE-07 a reply for a replaced queue is dropped`() = runTest {
        val harness = head(this, "a", "b")
        val reply = CompletableDeferred<WaveBatch>()
        val replies = ArrayDeque(listOf(reply))
        harness.source.onStartWave = { (replies.removeFirstOrNull() ?: CompletableDeferred()).await() }
        harness.engine.startPlaying()
        runCurrent()
        harness.controller.loadSource("Album") { tracks("c") }
        runCurrent()
        reply.complete(WaveBatch("V1", "B1", tracks("v1")))
        runCurrent()
        assertEquals(listOf("c"), harness.engine.ids())
        assertEquals(emptyList<String>(), harness.source.feedback)
    }

    @Test
    fun `CONT-04 WAVE-07 a queue that replaced one with a request in flight asks for its own vibe`() =
        runTest {
            val harness = head(this, "a", "b")
            harness.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
            harness.engine.startPlaying()
            runCurrent()
            harness.controller.loadSource("Album") { tracks("c", "d") }
            runCurrent()
            assertEquals(listOf("startWave [track:a]", "startWave [track:c]"), harness.source.calls)
        }

    @Test
    fun `CONT-04 WAVE-08 the head ends before the reply`() = runTest {
        val harness = head(this, "a", "b")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        assertTrue(harness.engine.isEnded)
        assertTrue("the footer shows while playback waits", harness.controller.state.value.loadingMore)
        reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
        runCurrent()
        assertEquals(2, harness.engine.currentIndex)
        assertEquals(
            listOf("append [v1, v2]", "seek 2", "prepare", "play"),
            harness.engine.commands.takeLast(4),
        )
        assertFalse(harness.controller.state.value.loadingMore)
    }

    @Test
    fun `CONT-04 WAVE-09 Next at the end after a failed load-more of the vibe asks again`() = runTest {
        val harness = head(this, "a", "b")
        val more = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1")) }
        harness.source.onMoreWave = { _, _ -> more.await() }
        harness.engine.startPlaying()
        runCurrent()
        repeat(3) { harness.engine.finishTrack() }
        runCurrent()
        assertTrue(harness.engine.isEnded)
        more.completeExceptionally(
            NetworkException("POST", "/rotor/session/V1/tracks", IOException("offline")),
        )
        runCurrent()
        assertTrue(harness.engine.isEnded)
        assertEquals(Stage.WAVE_MORE, (harness.events.last() as QueueEvent.Failed).stage)
        val attempts = harness.source.calls.size
        advanceTimeBy(10 * 60_000L)
        assertEquals("no retry on its own", attempts, harness.source.calls.size)

        val replies = ArrayDeque(listOf(WaveBatch("V1", "B3", tracks("m1", "m2"))))
        harness.source.onMoreWave =
            { _, _ -> replies.removeFirstOrNull() ?: CompletableDeferred<WaveBatch>().await() }
        harness.engine.commands.clear()
        harness.controller.next()
        runCurrent()
        assertEquals("moreWave V1 [a, b, v1]", harness.source.calls[attempts])
        assertEquals(listOf("append [m1, m2]", "seek 3", "prepare", "play"), harness.engine.commands)
        assertEquals(3, harness.engine.currentIndex)
    }

    @Test
    fun `CONT-05 seeds - five latest distinct started tracks, latest first, track id`() = runTest {
        val harness = head(this, "a", "b", "c", "d", "e", "f", "g")
        harness.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
        harness.engine.startPlaying()
        repeat(4) { harness.engine.finishTrack() }
        harness.engine.seekTo(1)
        runCurrent()
        assertEquals(emptyList<String>(), harness.source.calls)
        harness.engine.seekTo(5)
        runCurrent()
        assertEquals(listOf("startWave [track:f, track:b, track:e, track:d, track:c]"), harness.source.calls)
    }

    @Test
    fun `CONT-05 the vibe's own tracks are not seeds, also with the same id in the head`() = runTest {
        val first = head(this, "a", "b")
        first.source.onStartWave = { WaveBatch("V1", "B1", tracks("b", "v2", "v3")) }
        first.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        first.engine.startPlaying()
        runCurrent()
        repeat(3) { first.engine.finishTrack() }
        first.engine.stopPlaying()
        runCurrent()
        assertEquals(listOf("b", "a"), first.store.snapshot()!!.history)

        val second = QueueHarness(this, store = first.store, continueWithVibe = true)
        second.source.onStartWave = { WaveBatch("V2", "C1", tracks("n1")) }
        second.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        runCurrent()
        assertEquals("startWave [track:b, track:a]", second.source.calls.first())
    }

    @Test
    fun `CONT-06 no started track, no request, and the head stops at its end`() = runTest {
        val harness = head(this, "a", "b")
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        assertTrue(harness.engine.isEnded)
        harness.engine.commands.clear()
        harness.controller.next()
        runCurrent()
        assertEquals(emptyList<String>(), harness.source.calls)
        assertEquals(listOf("pause", "seek to 0"), harness.engine.commands)
        assertEquals(1, harness.engine.currentIndex)
    }

    @Test
    fun `CONT-07 a failed first request shows a vibe error, the head plays out without asking and stops`() =
        runTest {
            val harness = head(this, "a", "b", "c")
            harness.source.onStartWave =
                { throw MalformedResponseException("POST", "/rotor/session/new", "no radioSessionId") }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(Stage.WAVE, (harness.events.single() as QueueEvent.Failed).stage)
            assertEquals(1, harness.logged.size)
            harness.engine.finishTrack()
            harness.engine.finishTrack()
            runCurrent()
            assertTrue(harness.engine.isEnded)
            assertEquals(1, harness.source.calls.size)
            assertEquals(listOf("a", "b", "c"), harness.engine.ids())
        }

    @Test
    fun `CONT-07 an empty first reply shows nothing, and the head plays out without asking`() = runTest {
        val harness = head(this, "a", "b", "c")
        harness.source.onStartWave = { WaveBatch("V1", "B1", listOf(track("x", available = false))) }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        assertTrue(harness.engine.isEnded)
        assertEquals(emptyList<QueueEvent>(), harness.events)
        assertEquals(emptyList<String>(), harness.source.feedback)
        assertEquals(1, harness.source.calls.size)
    }

    @Test
    fun `CONT-07 Next at the end asks again with the seeds as they are then, and plays`() = runTest {
        val harness = head(this, "a", "b", "c")
        harness.source.onStartWave = { throw IllegalStateException("offline") }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1")) }
        harness.engine.commands.clear()
        harness.controller.next()
        runCurrent()
        assertEquals(QueueEvent.LoadingMore, harness.events.last())
        assertEquals("startWave [track:c, track:b, track:a]", harness.source.calls[1])
        assertEquals(listOf("append [v1]", "seek 3", "prepare", "play"), harness.engine.commands)
        assertEquals(3, harness.engine.currentIndex)
    }

    @Test
    fun `CONT-08 play-audio for every track, feedback only for vibe tracks, also for an id in both parts`() =
        runTest {
            val harness = head(this, "a", "b")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("a", "v2")) }
            harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            harness.engine.startPlaying()
            runCurrent()
            repeat(3) { harness.engine.finishTrack() }
            runCurrent()
            assertEquals(
                listOf("a" to "play-1", "b" to "play-2", "a" to "play-3", "v2" to "play-4"),
                harness.source.reports,
            )
            assertEquals(
                listOf(
                    "radioStarted V1/track:a/B1 - 0.0",
                    "trackStarted V1/track:a/B1 a 0.0",
                    "trackFinished V1/track:a/B1 a 0.0",
                    "trackStarted V1/track:a/B1 v2 0.0",
                ),
                harness.source.feedback,
            )
        }

    @Test
    fun `the entry the engine reports is the entry in state, after removing rows above, at and below it`() =
        runTest {
            val harness = head(this, "a", "b", "c")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("c", "z")) }
            harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("a", "b", "c", "c", "z"), harness.engine.ids())
            harness.source.feedback.clear()

            harness.controller.removeIndices(setOf(0, 1, 4))
            runCurrent()

            assertEquals(listOf("c", "c"), harness.engine.ids())
            assertEquals(0, harness.engine.currentIndex)
            assertEquals(EntryPart.HEAD, harness.controller.state.value.entries[0].part)
            assertEquals(emptyList<String>(), harness.source.feedback)
            assertEquals(listOf("c", "b", "a"), harness.store.snapshot()!!.history)
        }

    @Test
    fun `ERR-07 a broken last head track waits for the vibe and plays its first track`() = runTest {
        val harness = head(this, "a", "b")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.events.clear()
        harness.engine.commands.clear()
        harness.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertEquals(listOf(QueueEvent.Failed(Stage.PLAYBACK, ErrorKind.TrackUnplayable)), harness.events)
        assertEquals(emptyList<String>(), harness.engine.commands)
        reply.complete(WaveBatch("V1", "B1", tracks("v1")))
        runCurrent()
        assertEquals(listOf("append [v1]", "seek 2", "prepare", "play"), harness.engine.commands)
    }

    @Test
    fun `ERR-07 a track picked after a broken last head track keeps playing when the vibe comes`() = runTest {
        val harness = head(this, "a", "b")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.engine.fail(FailureKind.TRACK)
        runCurrent()
        assertTrue(harness.controller.state.value.loadingMore)
        harness.engine.seekTo(0)
        harness.engine.commands.clear()
        reply.complete(WaveBatch("V1", "B1", tracks("v1")))
        runCurrent()
        assertEquals(listOf("append [v1]"), harness.engine.commands)
        assertEquals(0, harness.engine.currentIndex)
        assertFalse(harness.controller.state.value.loadingMore)
    }

    @Test
    fun `ERR-07 a broken last head track that plays after all keeps playing when the vibe comes`() = runTest {
        val harness = head(this, "a", "b")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.engine.fail(FailureKind.TRACK)
        harness.engine.stopPlaying()
        harness.engine.startPlaying()
        assertFalse(harness.controller.state.value.loadingMore)
        harness.engine.commands.clear()
        reply.complete(WaveBatch("V1", "B1", tracks("v1")))
        runCurrent()
        assertEquals(listOf("append [v1]"), harness.engine.commands)
        assertEquals(1, harness.engine.currentIndex)
    }

    @Test
    fun `ERR-07 a broken last head track stops when the vibe cannot start, with no seeds or stalled`() =
        runTest {
            val unplayed = head(this, "a")
            unplayed.engine.fail(FailureKind.TRACK)
            runCurrent()
            assertEquals(listOf(QueueEvent.StoppedAfterFailures(1)), unplayed.events)
            assertEquals(emptyList<String>(), unplayed.source.calls)

            val stalled = head(this, "a", "b")
            stalled.source.onStartWave = { throw IllegalStateException("offline") }
            stalled.engine.startPlaying()
            runCurrent()
            stalled.engine.finishTrack()
            stalled.engine.fail(FailureKind.TRACK)
            runCurrent()
            assertEquals(QueueEvent.StoppedAfterFailures(1), stalled.events.last())
            assertEquals(1, stalled.source.calls.size)
        }

    @Test
    fun `CONT-09 shuffle orders the head only, and the vibe follows in its own order`() = runTest {
        val harness = head(this, "a", "b", "c", "d")
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2", "v3")) }
        harness.controller.setShuffle(true)
        harness.engine.shuffleOrder = listOf(0, 3, 1, 2)
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("startWave [track:b, track:d, track:a]"), harness.source.calls)
        assertEquals(listOf(0, 3, 1, 2, 4, 5, 6), harness.engine.playOrder())
        repeat(4) { harness.engine.finishTrack() }
        runCurrent()
        assertEquals(
            listOf("a", "d", "b", "c", "v1", "v2", "v3"),
            harness.source.reports.map { it.first },
        )
    }

    @Test
    fun `CONT-09 shuffle on starts a pass with the current track, each head track plays before the vibe`() =
        runTest {
            val harness = head(this, "a", "b", "c", "d")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1")) }
            harness.engine.seekTo(2)
            harness.controller.setShuffle(true)
            harness.engine.startPlaying()
            repeat(4) {
                harness.engine.finishTrack()
                runCurrent()
            }
            assertEquals(listOf("c", "a", "b", "d", "v1"), harness.source.reports.map { it.first })
        }

    @Test
    fun `CONT-09 shuffle switched on again while on keeps the pass`() = runTest {
        val harness = head(this, "a", "b", "c", "d")
        harness.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
        harness.controller.setShuffle(true)
        harness.engine.shuffleOrder = listOf(0, 3, 1, 2)
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        assertEquals(3, harness.engine.currentIndex)
        harness.controller.setShuffle(true)
        assertEquals(listOf(0, 3, 1, 2), harness.engine.playOrder())
    }

    @Test
    fun `CONT-09 WAVE-10 shuffle stays on for a head queue with a vibe, and is off for My Vibe`() = runTest {
        val harness = head(this, "a", "b")
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.controller.setShuffle(true)
        harness.engine.startPlaying()
        runCurrent()
        harness.engine.seekTo(3)
        assertTrue(harness.engine.shuffleEnabled)
        assertEquals(listOf(0, 1, 2, 3), harness.engine.playOrder())
        harness.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
        harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        assertFalse(harness.engine.shuffleEnabled)
    }

    @Test
    fun `CONT-04 CONT-09 a reply that comes mid-pass goes behind the head, and the pass stays as it was`() =
        runTest {
            val harness = head(this, "a", "b", "c", "d", "e")
            val reply = CompletableDeferred<WaveBatch>()
            harness.source.onStartWave = { reply.await() }
            harness.controller.setShuffle(true)
            harness.engine.shuffleOrder = listOf(0, 4, 2, 1, 3)
            harness.engine.startPlaying()
            repeat(3) { harness.engine.finishTrack() }
            runCurrent()
            assertEquals(1, harness.engine.currentIndex)
            assertEquals(1, harness.source.calls.size)
            reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
            runCurrent()
            assertEquals(listOf(0, 4, 2, 1, 3, 5, 6), harness.engine.playOrder())
            harness.engine.finishTrack()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(
                listOf("a", "e", "c", "b", "d", "v1"),
                harness.source.reports.map { it.first },
            )
        }

    @Test
    fun `CONT-10 TR-05 repeat wraps the head, and the vibe does not start`() = runTest {
        val harness = head(this, "a", "b")
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1")) }
        harness.controller.setRepeat(true)
        harness.engine.startPlaying()
        repeat(5) { harness.engine.finishTrack() }
        harness.controller.next()
        runCurrent()
        assertEquals(emptyList<String>(), harness.source.calls)
        assertEquals(listOf("a", "b"), harness.engine.ids())
        assertEquals(0, harness.engine.currentIndex)
        assertTrue(harness.engine.repeatEnabled)
    }

    @Test
    fun `CONT-10 vibe tracks that came before repeat wait behind the head as the end, Next, Previous wrap`() =
        runTest {
            val harness = head(this, "a", "b", "c")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("a", "b", "c", "v1", "v2"), harness.engine.ids())
            harness.controller.setRepeat(true)
            assertFalse(harness.engine.repeatEnabled)
            assertFalse(harness.engine.pauseAtEndOfCurrent)
            harness.engine.finishTrack()
            assertTrue(harness.engine.pauseAtEndOfCurrent)
            harness.engine.finishTrack()
            assertEquals(0, harness.engine.currentIndex)
            assertTrue(harness.engine.playWhenReady)
            assertFalse(harness.engine.pauseAtEndOfCurrent)

            harness.engine.seekTo(2)
            harness.controller.next()
            assertEquals(0, harness.engine.currentIndex)
            harness.controller.previous()
            assertEquals(2, harness.engine.currentIndex)
            runCurrent()
            assertEquals(listOf("a", "b", "c", "a", "c", "a", "c"), harness.source.reports.map { it.first })
            assertEquals(1, harness.source.calls.size)
        }

    @Test
    fun `CONT-10 with repeat off again, the vibe tracks follow the head`() = runTest {
        val harness = head(this, "a", "b", "c")
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.controller.setRepeat(true)
        harness.engine.finishTrack()
        assertTrue(harness.engine.pauseAtEndOfCurrent)
        harness.controller.setRepeat(false)
        assertFalse(harness.engine.pauseAtEndOfCurrent)
        harness.engine.finishTrack()
        assertEquals(3, harness.engine.currentIndex)
    }

    @Test
    fun `CONT-10 a reply that comes after repeat was switched on waits behind the head`() = runTest {
        val harness = head(this, "a", "b", "c")
        val reply = CompletableDeferred<WaveBatch>()
        harness.source.onStartWave = { reply.await() }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.controller.setRepeat(true)
        assertTrue(harness.engine.repeatEnabled)
        reply.complete(WaveBatch("V1", "B1", tracks("v1", "v2")))
        runCurrent()
        assertFalse(harness.engine.repeatEnabled)
        harness.engine.finishTrack()
        harness.engine.finishTrack()
        assertEquals(0, harness.engine.currentIndex)
        assertEquals(listOf("a", "b", "c", "v1", "v2"), harness.engine.ids())
    }

    @Test
    fun `CONT-10 TR-05 a one-track head wraps by repeat, Next, an error skip, Previous, each a new start`() =
        runTest {
            val harness = head(this, "a")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            harness.engine.startPlaying()
            runCurrent()
            assertEquals(listOf("a", "v1", "v2"), harness.engine.ids())
            harness.controller.setRepeat(true)
            assertTrue(harness.engine.pauseAtEndOfCurrent)
            harness.engine.finishTrack()
            assertTrue(harness.engine.playWhenReady)
            harness.controller.next()
            harness.engine.commands.clear()
            harness.engine.fail(FailureKind.TRACK)
            assertEquals(listOf("seek to 0", "prepare", "play"), harness.engine.commands)
            harness.controller.previous()
            runCurrent()
            assertEquals(0, harness.engine.currentIndex)
            assertEquals(
                listOf("a" to "play-1", "a" to "play-2", "a" to "play-3", "a" to "play-4", "a" to "play-5"),
                harness.source.reports,
            )
            assertEquals(listOf("radioStarted V1/track:a/B1 - 0.0"), harness.source.feedback)
        }

    @Test
    fun `CONT-10 ERR-04 an error skip on the head's last track goes to its first track, not into the vibe`() =
        runTest {
            val harness = head(this, "a", "b")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1")) }
            harness.engine.startPlaying()
            runCurrent()
            harness.controller.setRepeat(true)
            harness.engine.finishTrack()
            harness.events.clear()
            harness.engine.commands.clear()
            harness.engine.fail(FailureKind.TRACK)
            runCurrent()
            assertEquals(0, harness.engine.currentIndex)
            assertEquals(listOf("seek 0", "pause at end false", "prepare", "play"), harness.engine.commands)
            assertEquals(listOf(QueueEvent.Failed(Stage.PLAYBACK, ErrorKind.TrackUnplayable)), harness.events)
        }

    @Test
    fun `CONT-10 CONT-09 with shuffle, repeat wraps the head's pass, and vibe tracks wait behind it`() =
        runTest {
            val harness = head(this, "a", "b", "c")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1")) }
            harness.controller.setShuffle(true)
            harness.engine.shuffleOrder = listOf(0, 2, 1)
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            assertEquals(listOf(0, 2, 1, 3), harness.engine.playOrder())
            harness.controller.setRepeat(true)
            assertFalse(harness.engine.pauseAtEndOfCurrent)
            harness.engine.finishTrack()
            assertEquals(1, harness.engine.currentIndex)
            assertTrue(harness.engine.pauseAtEndOfCurrent)
            harness.engine.finishTrack()
            assertEquals(0, harness.engine.currentIndex)
            harness.engine.seekTo(1)
            harness.controller.next()
            assertEquals(0, harness.engine.currentIndex)
            harness.controller.previous()
            assertEquals(1, harness.engine.currentIndex)
            runCurrent()
            assertEquals(listOf("a", "c", "b", "a", "b", "a", "b"), harness.source.reports.map { it.first })
        }

    @Test
    fun `CONT-10 removing every vibe track behind a repeated head gives the engine its repeat back`() =
        runTest {
            val harness = head(this, "a", "b", "c")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            harness.controller.setRepeat(true)
            harness.engine.finishTrack()
            assertFalse(harness.engine.repeatEnabled)
            assertTrue(harness.engine.pauseAtEndOfCurrent)
            harness.controller.removeIndices(setOf(3, 4))
            assertTrue(harness.engine.repeatEnabled)
            assertFalse(harness.engine.pauseAtEndOfCurrent)
            harness.engine.finishTrack()
            assertEquals(0, harness.engine.currentIndex)
        }

    @Test
    fun `CONT-10 removing the head's last track pauses at the end of the one before it`() = runTest {
        val harness = head(this, "a", "b", "c")
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        runCurrent()
        harness.controller.setRepeat(true)
        assertFalse(harness.engine.pauseAtEndOfCurrent)
        harness.controller.removeIndices(setOf(2))
        assertTrue(harness.engine.pauseAtEndOfCurrent)
        harness.engine.finishTrack()
        assertEquals(0, harness.engine.currentIndex)
        assertEquals(listOf("a", "b", "v1", "v2"), harness.engine.ids())
    }

    @Test
    fun `CONT-10 TR-02 Previous after 3 s on the head's first track restarts it and does not wrap`() =
        runTest {
            val harness = head(this, "a", "b", "c")
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            harness.engine.startPlaying()
            harness.engine.finishTrack()
            runCurrent()
            harness.controller.setRepeat(true)
            harness.engine.seekTo(0)
            harness.engine.positionMs = 5_000
            harness.engine.commands.clear()
            harness.controller.previous()
            assertEquals(listOf("seek to 0"), harness.engine.commands)
            assertEquals(0, harness.engine.currentIndex)
        }

    @Test
    fun `CONT-10 repeat chosen on an empty queue reaches the engine at once and holds the next head`() =
        runTest {
            val harness = QueueHarness(this, continueWithVibe = true)
            harness.controller.setRepeat(true)
            assertTrue(harness.engine.repeatEnabled)
            harness.controller.loadSource("Liked") { tracks("a", "b") }
            runCurrent()
            assertEquals(listOf("repeat true", "set [a, b] play=true"), harness.engine.commands)
        }

    @Test
    fun `CONT-10 a pause at the end that no longer wraps the head just plays on`() = runTest {
        val harness = head(this, "a", "b")
        harness.engine.commands.clear()
        harness.controller.onPausedAtEnd()
        assertEquals(listOf("play"), harness.engine.commands)
        assertEquals(0, harness.engine.currentIndex)
    }

    @Test
    fun `CONT-11 in the vibe shuffle does not reorder, repeat does not wrap, the last track waits`() =
        runTest {
            val harness = head(this, "a", "b")
            val more = CompletableDeferred<WaveBatch>()
            val replies = ArrayDeque(listOf(more))
            harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2", "v3")) }
            harness.source.onMoreWave =
                { _, _ -> (replies.removeFirstOrNull() ?: CompletableDeferred()).await() }
            harness.controller.setShuffle(true)
            harness.engine.startPlaying()
            runCurrent()
            harness.engine.finishTrack()
            harness.engine.finishTrack()
            harness.controller.setRepeat(true)
            assertTrue(harness.engine.shuffleEnabled)
            assertFalse(harness.engine.repeatEnabled)
            assertFalse(harness.engine.pauseAtEndOfCurrent)
            assertEquals(listOf(0, 1, 2, 3, 4), harness.engine.playOrder())
            harness.controller.next()
            harness.engine.finishTrack()
            harness.engine.finishTrack()
            runCurrent()
            assertTrue(harness.engine.isEnded)
            assertEquals(4, harness.engine.currentIndex)
            assertEquals("moreWave V1 [a, b, v1, v2, v3]", harness.source.calls.last())
            more.complete(WaveBatch("", "B2", tracks("m1")))
            runCurrent()
            assertEquals(5, harness.engine.currentIndex)
            assertEquals(
                listOf("append [m1]", "seek 5", "prepare", "play"),
                harness.engine.commands.takeLast(4),
            )
        }

    private fun head(scope: TestScope, vararg ids: String, flag: Boolean = true): QueueHarness {
        val harness = QueueHarness(scope, continueWithVibe = flag)
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.controller.loadSource("Liked") { tracks(*ids) }
        scope.runCurrent()
        harness.events.clear()
        harness.engine.commands.clear()
        return harness
    }

    private fun stoppedAtTheEnd(scope: TestScope, byNext: Boolean): QueueHarness {
        val harness = head(scope, "a", "b", flag = false)
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        if (byNext) harness.controller.next() else harness.engine.finishTrack()
        scope.runCurrent()
        assertEquals(1, harness.engine.currentIndex)
        assertEquals(emptyList<String>(), harness.source.calls)
        return harness
    }
}
