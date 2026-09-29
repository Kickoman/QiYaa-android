package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTrackerTest {
    private var now = 0L
    private val tracker = PlayTracker { now }

    @Test
    fun `a current track starts on the first playing after it became current`() {
        tracker.onItemChanged(track("a"), Transition.NEW_QUEUE, isPlaying = false)
        assertEquals(track("a"), tracker.onPlayingChanged(true))
        assertEquals(null, tracker.onPlayingChanged(false))
        assertEquals(null, tracker.onPlayingChanged(true))
    }

    @Test
    fun `a track that becomes current while audio plays starts at once`() {
        tracker.onItemChanged(track("a"), Transition.NEW_QUEUE, isPlaying = false)
        tracker.onPlayingChanged(true)
        val change = tracker.onItemChanged(track("b"), Transition.AUTO, isPlaying = true)
        assertEquals(track("b"), change.started)
        assertEquals(true, change.closed?.finished)
    }

    @Test
    fun `TRK-08 played seconds count only playing time`() {
        tracker.onItemChanged(track("a"), Transition.NEW_QUEUE, isPlaying = false)
        now = 1_000
        tracker.onPlayingChanged(true)
        now = 11_000
        tracker.onPlayingChanged(false)
        now = 20_000
        tracker.onPlayingChanged(true)
        now = 22_500
        val closed = tracker.onItemChanged(track("b"), Transition.SEEK, isPlaying = true).closed!!
        assertEquals(12.5, closed.playedSeconds, 1e-9)
        assertEquals(false, closed.finished)
    }

    @Test
    fun `a restart closes the track as a skip and starts it again`() {
        tracker.onItemChanged(track("a"), Transition.NEW_QUEUE, isPlaying = false)
        tracker.onPlayingChanged(true)
        val change = tracker.onRestart()
        assertEquals(false, change.closed?.finished)
        assertEquals(track("a"), change.started)
    }

    @Test
    fun `the end closes the track finished and nothing starts until it plays again`() {
        tracker.onItemChanged(track("a"), Transition.NEW_QUEUE, isPlaying = false)
        tracker.onPlayingChanged(true)
        val change = tracker.onEnded()
        assertEquals(true, change.closed?.finished)
        assertEquals(null, change.started)
        tracker.onPlayingChanged(false)
        assertEquals(track("a"), tracker.onPlayingChanged(true))
    }

    @Test
    fun `nothing starts or closes without a current track`() {
        assertEquals(null, tracker.onRestart().started)
        assertEquals(null, tracker.onPlayingChanged(true))
        assertEquals(null, tracker.closeAsSkip())
    }
}
