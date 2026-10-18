package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.FakeQueueStore
import io.github.kickoman.qiyaa.support.QueueHarness
import io.github.kickoman.qiyaa.support.QueueHarness.Companion.tracks
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueShuffleTest {
    @Test
    fun `SHUF-02 Previous plays the track before in the pass, played or not`() = runTest {
        val h = head(this, "a", "b", "c", "d", "e")
        h.controller.setShuffle(true)
        h.engine.shuffleOrder = listOf(0, 3, 1, 4, 2)
        h.engine.seekTo(4)
        h.controller.previous()
        assertEquals(1, h.engine.currentIndex)
        h.controller.previous()
        assertEquals(3, h.engine.currentIndex)
        h.controller.previous()
        assertEquals(0, h.engine.currentIndex)
        assertEquals("previous", h.engine.commands.last())
    }

    @Test
    fun `SHUF-02 TR-01 on the pass's first track Previous restarts it as a new play`() = runTest {
        val h = head(this, "a", "b", "c", "d")
        h.engine.seekTo(2)
        h.controller.setShuffle(true)
        assertEquals(listOf(2, 0, 1, 3), h.engine.playOrder())
        h.engine.startPlaying()
        h.engine.positionMs = 1_000
        h.controller.previous()
        h.engine.startPlaying()
        runCurrent()
        assertEquals(2, h.engine.currentIndex)
        assertEquals("seek to 0", h.engine.commands.last())
        assertEquals(listOf("c" to "play-1", "c" to "play-2"), h.source.reports)
    }

    @Test
    fun `SHUF-02 TR-05 with repeat, Previous on the pass's first track goes to the pass's last`() = runTest {
        val h = head(this, "a", "b", "c", "d", "e")
        h.controller.setShuffle(true)
        h.engine.shuffleOrder = listOf(0, 3, 1, 4, 2)
        h.controller.setRepeat(true)
        h.controller.previous()
        assertEquals(2, h.engine.currentIndex)
        assertEquals("previous", h.engine.commands.last())
    }

    @Test
    fun `SHUF-02 a picked track plays and the pass goes on from its place`() = runTest {
        val h = head(this, "a", "b", "c", "d", "e")
        h.controller.setShuffle(true)
        h.engine.shuffleOrder = listOf(0, 3, 1, 4, 2)
        h.engine.startPlaying()
        h.engine.seekTo(1)
        h.controller.next()
        assertEquals(4, h.engine.currentIndex)
        h.engine.finishTrack()
        assertEquals(2, h.engine.currentIndex)
        h.controller.next()
        runCurrent()
        assertEquals(2, h.engine.currentIndex)
        assertEquals(listOf("pause", "seek to 0"), h.engine.commands.takeLast(2))
        assertEquals(listOf("a", "b", "e", "c"), h.source.reports.map { it.first })
    }

    @Test
    fun `SHUF-03 shuffle switched off plays the head in source order from the current track`() = runTest {
        val h = head(this, "a", "b", "c", "d", "e")
        h.controller.setShuffle(true)
        h.engine.shuffleOrder = listOf(0, 3, 1, 4, 2)
        h.engine.seekTo(3)
        h.controller.setShuffle(false)
        assertEquals(listOf(0, 1, 2, 3, 4), h.engine.playOrder())
        h.controller.next()
        assertEquals(4, h.engine.currentIndex)
        h.controller.next()
        assertEquals(4, h.engine.currentIndex)
        assertEquals(listOf("pause", "seek to 0"), h.engine.commands.takeLast(2))
    }

    @Test
    fun `SHUF-03 switching keeps the position, the pause and the play reports`() = runTest {
        val h = head(this, "a", "b", "c")
        h.engine.seekTo(1)
        h.engine.startPlaying()
        h.engine.positionMs = 42_000
        h.engine.pause()
        runCurrent()
        h.engine.commands.clear()
        h.controller.setShuffle(true)
        h.controller.setShuffle(false)
        h.controller.setShuffle(true)
        runCurrent()
        assertEquals(listOf("shuffle true", "shuffle false", "shuffle true"), h.engine.commands)
        assertEquals(1, h.engine.currentIndex)
        assertEquals(42_000L, h.engine.positionMs)
        assertFalse(h.engine.playWhenReady)
        assertEquals(listOf("b" to "play-1"), h.source.reports)
        assertEquals(emptyList<String>(), h.source.feedback)
        assertEquals(emptyList<QueueEvent>(), h.events)
    }

    @Test
    fun `SHUF-02 Previous on the first vibe track goes to the head track that was current last`() = runTest {
        val h = head(this, "a", "b", "c", "d", flag = true)
        h.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        h.controller.setShuffle(true)
        h.engine.shuffleOrder = listOf(0, 3, 1, 2)
        h.engine.startPlaying()
        h.engine.finishTrack()
        h.engine.finishTrack()
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d", "v1", "v2"), h.engine.ids())
        h.engine.seekTo(4)
        h.controller.previous()
        assertEquals(1, h.engine.currentIndex)
        assertEquals("seek 1", h.engine.commands.last())
        h.controller.next()
        assertEquals(2, h.engine.currentIndex)
        h.controller.next()
        assertEquals(4, h.engine.currentIndex)
    }

    @Test
    fun `SHUF-02 the head mark follows removed head rows, a removed mark moves to the head track before`() =
        runTest {
            val h = head(this, "a", "b", "c", "d", "e", flag = true)
            h.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            h.controller.setShuffle(true)
            h.engine.shuffleOrder = listOf(0, 3, 2, 1, 4)
            h.engine.startPlaying()
            repeat(3) { h.engine.finishTrack() }
            runCurrent()
            assertEquals(listOf("a", "b", "c", "d", "e", "v1", "v2"), h.engine.ids())
            h.engine.seekTo(2)
            h.engine.seekTo(5)
            h.controller.removeIndices(setOf(0))
            h.controller.previous()
            assertEquals("c", h.currentId())
            h.engine.seekTo(4)
            h.controller.removeIndices(setOf(1))
            h.controller.previous()
            assertEquals("b", h.currentId())
        }

    @Test
    fun `TR-01 with shuffle off, Previous on the first vibe track plays the row before, not the head mark`() =
        runTest {
            val h = head(this, "a", "b", "c", "d", flag = true)
            h.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            h.engine.startPlaying()
            h.engine.finishTrack()
            h.engine.finishTrack()
            runCurrent()
            assertEquals(listOf("a", "b", "c", "d", "v1", "v2"), h.engine.ids())
            h.engine.seekTo(1)
            h.engine.seekTo(4)
            h.controller.previous()
            assertEquals(3, h.engine.currentIndex)
        }

    @Test
    fun `SHUF-04 removing head tracks starts a new pass from the current track, heard ones may return`() =
        runTest {
            val h = head(this, "a", "b", "c", "d", "e")
            h.controller.setShuffle(true)
            h.engine.shuffleOrder = listOf(0, 3, 1, 4, 2)
            h.engine.startPlaying()
            h.controller.next()
            assertEquals("d", h.currentId())
            h.controller.removeIndices(setOf(4))
            assertEquals(listOf(3), h.engine.leads.last())
            assertEquals(listOf(3, 0, 1, 2), h.engine.playOrder())
            h.controller.next()
            runCurrent()
            assertEquals("a", h.currentId())
            assertEquals(listOf("a", "d", "a"), h.source.reports.map { it.first })
        }

    @Test
    fun `SHUF-04 removing the current head track starts the pass from the track that becomes current`() =
        runTest {
            val h = head(this, "a", "b", "c", "d", "e")
            h.controller.setShuffle(true)
            h.engine.shuffleOrder = listOf(0, 3, 1, 4, 2)
            h.engine.startPlaying()
            h.controller.next()
            h.controller.removeIndices(setOf(3))
            assertEquals("b", h.currentId())
            assertEquals(listOf(1), h.engine.leads.last())
            assertEquals(listOf(1, 0, 2, 3), h.engine.playOrder())
        }

    @Test
    fun `SHUF-04 removing vibe tracks leaves the pass as it is`() = runTest {
        val h = head(this, "a", "b", "c", "d", flag = true)
        h.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2", "v3")) }
        h.controller.setShuffle(true)
        h.engine.shuffleOrder = listOf(0, 3, 1, 2)
        h.engine.startPlaying()
        h.engine.finishTrack()
        h.engine.finishTrack()
        runCurrent()
        val leads = h.engine.leads.size
        h.controller.removeIndices(setOf(5))
        assertEquals(listOf("a", "b", "c", "d", "v1", "v3"), h.engine.ids())
        assertEquals(leads, h.engine.leads.size)
        assertEquals(listOf(0, 3, 1, 2), h.engine.playOrder().take(4))
    }

    @Test
    fun `SHUF-04 with a vibe track current, removing a head track starts the pass from the head mark`() =
        runTest {
            val h = head(this, "a", "b", "c", "d", "e", flag = true)
            h.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
            h.controller.setShuffle(true)
            h.engine.shuffleOrder = listOf(0, 3, 2, 1, 4)
            h.engine.startPlaying()
            repeat(3) { h.engine.finishTrack() }
            runCurrent()
            h.engine.seekTo(2)
            h.engine.seekTo(5)
            h.controller.removeIndices(setOf(3))
            assertEquals(listOf(2), h.engine.leads.last())
            assertEquals(listOf(2, 0, 1, 3, 4, 5), h.engine.playOrder())
            h.controller.previous()
            assertEquals("c", h.currentId())
            val played = (1..4).map {
                h.controller.next()
                h.currentId()
            }
            assertEquals(listOf("a", "b", "e", "v1"), played)
        }

    @Test
    fun `SHUF-04 with Random track removing the drawn track draws again, and it never plays`() = runTest {
        val h = randomHead(this, "a", "b", "c", "d")
        val drawn = h.engine.leads.last().last()
        val removed = h.engine.tracks[drawn].id
        h.controller.removeIndices(setOf(drawn))
        val lead = h.engine.leads.last()
        assertEquals(0, lead.first())
        assertEquals(2, lead.size)
        assertTrue(lead.last() in 1..2)
        val played = (1..20).map {
            h.controller.next()
            h.currentId()
        }
        assertFalse(removed in played)
    }

    @Test
    fun `SHUF-04 with Random track removing the current track plays the draw and draws from it`() = runTest {
        val h = randomHead(this, "a", "b", "c", "d")
        h.controller.next()
        val current = h.engine.currentIndex
        val drawn = h.engine.leads.last().last()
        val drawnId = h.engine.tracks[drawn].id
        h.controller.removeIndices(setOf(current))
        assertEquals(drawnId, h.currentId())
        assertEquals(h.engine.currentIndex, h.engine.leads.last().first())
        assertEquals(2, h.engine.leads.last().size)
        assertNotEquals(h.engine.currentIndex, h.engine.leads.last().last())
    }

    @Test
    fun `SHUF-04 with Random track removing a vibe track keeps the draw`() = runTest {
        val h = withVibe(this)
        h.shuffleRandomly()
        val leads = h.engine.leads.size
        val next = h.engine.playOrder()[1]
        h.controller.removeIndices(setOf(4))
        assertEquals(leads, h.engine.leads.size)
        assertEquals(next, h.engine.playOrder()[1])
    }

    @Test
    fun `SHUF-05 with Random track the staged next is what Next and the end of the track play`() = runTest {
        val h = randomHead(this, "a", "b", "c", "d")
        val drawn = h.engine.leads.last().last()
        assertEquals(listOf(0, drawn), h.engine.leads.last())
        assertEquals(drawn, h.engine.playOrder()[1])
        assertFalse(h.engine.pauseAtEndOfCurrent)
        h.controller.next()
        assertEquals("next", h.engine.commands.last())
        assertEquals(drawn, h.engine.currentIndex)
        val following = h.engine.leads.last().last()
        assertEquals(listOf(drawn, following), h.engine.leads.last())
        assertFalse(h.engine.pauseAtEndOfCurrent)
        h.engine.finishTrack()
        assertEquals(following, h.engine.currentIndex)
        assertFalse(h.engine.pauseAtEndOfCurrent)
    }

    @Test
    fun `SHUF-05 a pause, a seek in the track and repeat do not draw again`() = runTest {
        val h = randomHead(this, "a", "b", "c", "d")
        h.engine.startPlaying()
        val leads = h.engine.leads.size
        val next = h.engine.playOrder()[1]
        h.engine.pause()
        h.engine.play()
        h.engine.startPlaying()
        h.engine.seekToPosition(30_000)
        h.controller.setRepeat(true)
        h.controller.setRepeat(false)
        assertEquals(leads, h.engine.leads.size)
        assertEquals(next, h.engine.playOrder()[1])
    }

    @Test
    fun `SHUF-06 Next draws another head track, each with the same chance`() = runTest {
        val h = randomHead(this, "a", "b", "c", "d")
        val counts = HashMap<Pair<Int, Int>, Int>()
        val played = mutableListOf(h.engine.currentIndex)
        repeat(3_000) {
            val from = h.engine.currentIndex
            h.controller.next()
            val to = h.engine.currentIndex
            assertNotEquals(from, to)
            counts.merge(from to to, 1, Int::plus)
            played += to
        }
        assertEquals(12, counts.size)
        for ((pair, count) in counts) assertTrue("$pair came $count times", count in 200..300)
        assertTrue(played.windowed(4).any { it.toSet().size < 4 })
    }

    @Test
    fun `SHUF-06 CONT-10 repeat does not change Next, also with vibe tracks behind the head`() = runTest {
        val h = withVibe(this)
        h.shuffleRandomly()
        val drawn = h.engine.playOrder()[1]
        h.controller.setRepeat(true)
        assertFalse(h.engine.repeatEnabled)
        assertFalse(h.engine.pauseAtEndOfCurrent)
        assertEquals(drawn, h.engine.playOrder()[1])
        h.controller.next()
        assertEquals("next", h.engine.commands.last())
        assertEquals(drawn, h.engine.currentIndex)
        assertFalse(h.engine.pauseAtEndOfCurrent)
    }

    @Test
    fun `SHUF-06 TR-04 TR-05 CONT-04 a head of one track plays as without shuffle`() = runTest {
        val vibe = head(this, "a", flag = true)
        vibe.source.onStartWave = { CompletableDeferred<WaveBatch>().await() }
        vibe.shuffleRandomly()
        vibe.engine.startPlaying()
        runCurrent()
        assertEquals(listOf("startWave [track:a]"), vibe.source.calls)

        val stops = head(this, "a")
        stops.shuffleRandomly()
        stops.engine.startPlaying()
        stops.controller.next()
        assertEquals(listOf("pause", "seek to 0"), stops.engine.commands.takeLast(2))
        stops.controller.setRepeat(true)
        stops.engine.startPlaying()
        stops.controller.next()
        runCurrent()
        assertEquals("next", stops.engine.commands.last())
        assertEquals(listOf("a" to "play-1", "a" to "play-2", "a" to "play-3"), stops.source.reports)
    }

    @Test
    fun `SHUF-07 Previous goes to the head track before in source order, not the one that played`() =
        runTest {
            val h = randomHead(this, "a", "b", "c", "d", "e")
            h.engine.seekTo(4)
            h.engine.seekTo(3)
            h.controller.previous()
            assertEquals(2, h.engine.currentIndex)
            assertEquals("seek 2", h.engine.commands.last())
            assertEquals(2, h.engine.leads.last().first())
        }

    @Test
    fun `SHUF-07 on the head's first track Previous restarts without repeat, goes to the last with it`() =
        runTest {
            val h = randomHead(this, "a", "b", "c", "d", "e")
            h.engine.startPlaying()
            h.engine.positionMs = 1_000
            h.controller.previous()
            h.engine.startPlaying()
            runCurrent()
            assertEquals(0, h.engine.currentIndex)
            assertEquals("seek to 0", h.engine.commands.last())
            assertEquals(listOf("a" to "play-1", "a" to "play-2"), h.source.reports)
            h.controller.setRepeat(true)
            h.engine.positionMs = 1_000
            h.controller.previous()
            assertEquals(4, h.engine.currentIndex)
            h.engine.positionMs = 3_001
            h.controller.previous()
            runCurrent()
            assertEquals(4, h.engine.currentIndex)
            assertEquals("seek to 0", h.engine.commands.last())
            assertEquals(listOf("a", "a", "e"), h.source.reports.map { it.first })
        }

    @Test
    fun `SHUF-07 Previous on the first vibe track goes to the head track that was current last`() = runTest {
        val h = withVibe(this)
        h.engine.seekTo(0)
        h.shuffleRandomly()
        h.engine.seekTo(3)
        h.controller.previous()
        assertEquals(0, h.engine.currentIndex)
        assertEquals(0, h.engine.leads.last().first())
        assertEquals(2, h.engine.leads.last().size)
    }

    @Test
    fun `SHUF-08 the head never runs out, remaining is endless and the vibe from history never starts`() =
        runTest {
            val h = randomHead(this, "a", "b", "c", flag = true)
            h.engine.startPlaying()
            repeat(30) {
                h.engine.finishTrack()
                runCurrent()
            }
            assertEquals(emptyList<String>(), h.source.calls)
            assertFalse(h.engine.isEnded)
            assertEquals(listOf("a", "b", "c"), h.engine.ids())
            assertEquals(31, h.source.reports.size)
        }

    @Test
    fun `SHUF-08 CONT-11 vibe tracks in the queue wait until picked, then the vibe goes on in its order`() =
        runTest {
            val h = withVibe(this)
            h.shuffleRandomly()
            repeat(10) {
                h.engine.finishTrack()
                assertTrue(h.engine.currentIndex < 3)
            }
            h.engine.seekTo(3)
            h.controller.next()
            runCurrent()
            assertEquals(4, h.engine.currentIndex)
            assertTrue(h.source.calls.last().startsWith("moreWave V1"))
        }

    @Test
    fun `SHUF-09 choosing Random track with shuffle on draws from the current track, nothing else changes`() =
        runTest {
            val h = head(this, "a", "b", "c", "d", "e")
            h.controller.setShuffle(true)
            h.engine.seekTo(2)
            h.engine.startPlaying()
            h.engine.positionMs = 42_000
            h.engine.pause()
            runCurrent()
            h.engine.commands.clear()
            h.controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
            runCurrent()
            val lead = h.engine.leads.last()
            assertEquals(2, lead.first())
            assertEquals(2, lead.size)
            assertNotEquals(2, lead.last())
            assertEquals(lead.last(), h.engine.playOrder()[1])
            assertEquals(emptyList<String>(), h.engine.commands)
            assertEquals(42_000L, h.engine.positionMs)
            assertFalse(h.engine.playWhenReady)
            assertEquals(listOf("c" to "play-1"), h.source.reports)
            assertTrue(h.controller.modes.value.shuffle)
            assertEquals(emptyList<QueueEvent>(), h.events)
        }

    @Test
    fun `SHUF-09 SHUF-03 choosing does not switch shuffle on, and applies once shuffle does`() = runTest {
        val h = head(this, "a", "b", "c", "d", flag = true)
        val leads = h.engine.leads.size
        h.controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
        assertFalse(h.controller.modes.value.shuffle)
        assertFalse(h.engine.shuffleEnabled)
        assertEquals(listOf(0, 1, 2, 3), h.engine.playOrder())
        assertEquals(leads, h.engine.leads.size)
        h.controller.setShuffle(true)
        val lead = h.engine.leads.last()
        assertEquals(0, lead.first())
        assertEquals(2, lead.size)
        assertEquals(lead.last(), h.engine.playOrder()[1])
        h.engine.startPlaying()
        repeat(5) { h.engine.finishTrack() }
        runCurrent()
        assertEquals(emptyList<String>(), h.source.calls)
    }

    @Test
    fun `SHUF-09 back to Without repeats starts a pass from the current track`() = runTest {
        val h = randomHead(this, "a", "b", "c", "d")
        h.controller.next()
        val current = h.engine.currentIndex
        h.controller.setShuffleAlgorithm(ShuffleAlgorithm.WITHOUT_REPEATS)
        assertEquals(listOf(current), h.engine.leads.last())
        val played = (1..3).map {
            h.controller.next()
            h.engine.currentIndex
        }
        assertEquals((0..3).toSet() - current, played.toSet())
        h.controller.next()
        assertEquals(listOf("pause", "seek to 0"), h.engine.commands.takeLast(2))
    }

    @Test
    fun `SHUF-09 WAVE-11 a choice made in a wave is kept for the next ordinary queue`() = runTest {
        val h = QueueHarness(this)
        h.controller.setShuffle(true)
        h.source.onStartWave = { WaveBatch("S1", "B1", tracks("w1", "w2", "w3")) }
        h.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        h.controller.playWave(listOf("user:onyourwave"), "My Wave")
        runCurrent()
        val leads = h.engine.leads.size
        h.controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
        assertEquals(leads, h.engine.leads.size)
        assertFalse(h.engine.shuffleEnabled)
        assertEquals(listOf(0, 1, 2), h.engine.playOrder())
        h.controller.loadSource("Liked") { tracks("a", "b", "c", "d") }
        runCurrent()
        val lead = h.engine.leads.last()
        assertEquals(0, lead.first())
        assertEquals(2, lead.size)
        assertEquals(lead.last(), h.engine.playOrder()[1])
    }

    @Test
    fun `SHUF-10 the algorithm is not saved with the queue`() = runTest {
        val h = head(this, "a", "b", "c")
        h.controller.setShuffle(true)
        runCurrent()
        val saved = h.store.text
        val writes = h.store.writes
        h.controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
        runCurrent()
        assertEquals(saved, h.store.text)
        assertEquals(writes, h.store.writes)
        val restored = QueueHarness(this, store = FakeQueueStore(saved))
        assertTrue(restored.engine.shuffleEnabled)
        assertEquals(listOf(listOf(0)), restored.engine.leads)
    }

    private fun head(scope: TestScope, vararg ids: String, flag: Boolean = false): QueueHarness {
        val harness = QueueHarness(scope, continueWithVibe = flag)
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.controller.loadSource("Liked") { tracks(*ids) }
        scope.runCurrent()
        harness.events.clear()
        harness.engine.commands.clear()
        return harness
    }

    private fun randomHead(scope: TestScope, vararg ids: String, flag: Boolean = false): QueueHarness =
        head(scope, *ids, flag = flag).also { it.shuffleRandomly() }

    private fun withVibe(scope: TestScope): QueueHarness {
        val harness = head(scope, "a", "b", "c", flag = true)
        harness.source.onStartWave = { WaveBatch("V1", "B1", tracks("v1", "v2")) }
        harness.engine.startPlaying()
        harness.engine.finishTrack()
        scope.runCurrent()
        assertEquals(listOf("a", "b", "c", "v1", "v2"), harness.engine.ids())
        return harness
    }

    private fun QueueHarness.shuffleRandomly() {
        controller.setShuffleAlgorithm(ShuffleAlgorithm.RANDOM_TRACK)
        controller.setShuffle(true)
    }

    private fun QueueHarness.currentId(): String = engine.tracks[engine.currentIndex].id
}
