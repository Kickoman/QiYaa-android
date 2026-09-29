package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.support.QueueHarness.Companion.track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTrackerTest {
    @Test
    fun `a current track starts on the first playing after it became current`() {
        val tracker = PlayTracker()
        tracker.onItemChanged(track("a"))
        assertEquals(track("a"), tracker.onPlayingChanged(true))
        assertEquals(null, tracker.onPlayingChanged(false))
        assertEquals(null, tracker.onPlayingChanged(true))
    }

    @Test
    fun `a restart makes the next playing a new start`() {
        val tracker = PlayTracker()
        tracker.onItemChanged(track("a"))
        tracker.onPlayingChanged(true)
        tracker.onRestart()
        assertEquals(track("a"), tracker.onPlayingChanged(true))
    }

    @Test
    fun `nothing starts without a current track`() {
        val tracker = PlayTracker()
        tracker.onRestart()
        assertEquals(null, tracker.onPlayingChanged(true))
        tracker.onItemChanged(null)
        assertEquals(null, tracker.onPlayingChanged(true))
    }
}
