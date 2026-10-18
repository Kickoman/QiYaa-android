package io.github.kickoman.qiyaa.queue

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

    private fun head(scope: TestScope, vararg ids: String, flag: Boolean = false): QueueHarness {
        val harness = QueueHarness(scope, continueWithVibe = flag)
        harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
        harness.controller.loadSource("Liked") { tracks(*ids) }
        scope.runCurrent()
        harness.events.clear()
        harness.engine.commands.clear()
        return harness
    }
}
