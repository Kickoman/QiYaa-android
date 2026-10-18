package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBufferTest {
    private val player = PlayerId("test")
    private val timeline = SinglePeriodTimeline(TRACK_US, true, false, false, null, MediaItem.EMPTY)

    @Test
    fun `TR-09 the player keeps the last 10 minutes behind the play position`() {
        val control = prepared(PlayerBuffer.loadControl())

        assertEquals(600_000_000L, control.getBackBufferDurationUs(player))
        assertFalse(control.retainBackBufferFromKeyframe(player))
    }

    @Test
    fun `TR-09 a seek with 3 s downloaded after the target starts playing, also after a rebuffer`() {
        val control = prepared(PlayerBuffer.loadControl())

        assertTrue(control.shouldStartPlayback(parameters(bufferedUs = 3_000_000, rebuffering = false)))
        assertTrue(control.shouldStartPlayback(parameters(bufferedUs = 3_000_000, rebuffering = true)))
    }

    @Test
    fun `TR-09 with the byte target full, a nearly empty buffer does not start playback`() {
        val withoutPriority =
            DefaultLoadControl.Builder()
                .setBackBuffer(PlayerBuffer.BACK_BUFFER_MS, false)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        val halfSecond = parameters(bufferedUs = 500_000, rebuffering = false)

        assertFalse(fillTarget(prepared(PlayerBuffer.loadControl())).shouldStartPlayback(halfSecond))
        assertTrue(fillTarget(prepared(withoutPriority)).shouldStartPlayback(halfSecond))
    }

    private fun prepared(control: DefaultLoadControl): DefaultLoadControl {
        control.onPrepared(player)
        return control
    }

    private fun fillTarget(control: DefaultLoadControl): DefaultLoadControl {
        val allocator = control.getAllocator(player)
        repeat(DEFAULT_TARGET_BLOCKS) { allocator.allocate() }
        assertEquals(DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE, allocator.totalBytesAllocated)
        return control
    }

    private fun parameters(bufferedUs: Long, rebuffering: Boolean) = LoadControl.Parameters(
        player,
        timeline,
        MediaPeriodId(timeline.getUidOfPeriod(0)),
        60_000_000,
        bufferedUs,
        1f,
        true,
        rebuffering,
        C.TIME_UNSET,
        C.TIME_UNSET,
    )

    private companion object {
        const val TRACK_US = 300_000_000L
        const val DEFAULT_TARGET_BLOCKS = 200
    }
}
