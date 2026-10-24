package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaSourcesTest {
    @Test
    fun `LOCAL-06 only a content URI of the media library keeps nothing it has played`() {
        assertTrue(LocalMediaSources.discardsPlayed("content://media/external/audio/media/42"))
        assertTrue(LocalMediaSources.discardsPlayed("CONTENT://media/external/audio/media/42"))
        assertFalse(LocalMediaSources.discardsPlayed("qiyaa://track/42"))
        assertFalse(LocalMediaSources.discardsPlayed("https://example.org/content://x"))
        assertFalse(LocalMediaSources.discardsPlayed(null))
    }

    @Test
    fun `LOCAL-06 the wrapped period is asked to discard up to the play position`() {
        val inner = RecordingPeriod()
        val outer = PlayedDiscardingPeriod(inner)
        val playingAtUs = 95_000_000L
        outer.discardBuffer(playingAtUs - PlayerBuffer.BACK_BUFFER_MS * 1_000L, toKeyframe = true)
        assertEquals(listOf(playingAtUs to true), inner.discards)
    }

    @Test
    fun `LOCAL-06 the wrapped period's callbacks name the outer period`() {
        val inner = RecordingPeriod()
        val outer = PlayedDiscardingPeriod(inner)
        val named = ArrayList<MediaPeriod>()
        outer.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) {
                    named += mediaPeriod
                }

                override fun onContinueLoadingRequested(source: MediaPeriod) {
                    named += source
                }
            },
            positionUs = 7L,
        )
        assertEquals(7L, inner.preparedAtUs)
        inner.callback!!.onPrepared(inner)
        inner.callback!!.onContinueLoadingRequested(inner)
        assertEquals(2, named.size)
        named.forEach { assertSame(outer, it) }
    }

    @Test
    fun `LOCAL-06 the optional period calls reach the wrapped period`() {
        val inner = RecordingPeriod()
        val outer = PlayedDiscardingPeriod(inner)
        outer.setUsesStreamPrerollFlags()
        assertTrue(inner.usesPrerollFlags)
        assertEquals(40L, outer.setEndPositionUs(41L))
        assertEquals(listOf(StreamKey(0, 1)), outer.getStreamKeys(emptyList()))
    }

    private class RecordingPeriod : MediaPeriod {
        val discards = ArrayList<Pair<Long, Boolean>>()
        var callback: MediaPeriod.Callback? = null
        var preparedAtUs = C.TIME_UNSET
        var usesPrerollFlags = false

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            preparedAtUs = positionUs
        }

        override fun maybeThrowPrepareError() = Unit

        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY

        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
            listOf(StreamKey(0, 1))

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long = positionUs

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
            discards += positionUs to toKeyframe
        }

        override fun readDiscontinuity(): Long = C.TIME_UNSET

        override fun setUsesStreamPrerollFlags() {
            usesPrerollFlags = true
        }

        override fun seekToUs(positionUs: Long): Long = positionUs

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
            positionUs

        override fun getBufferedPositionUs(): Long = 0L

        override fun getNextLoadPositionUs(): Long = 0L

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

        override fun isLoading(): Boolean = false

        override fun reevaluateBuffer(positionUs: Long) = Unit

        override fun setEndPositionUs(endPositionUs: Long): Long = endPositionUs - 1
    }
}
