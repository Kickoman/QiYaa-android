package io.github.kickoman.qiyaa.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentBitrateTest {
    private val shown = ArrayList<Int>()
    private val bitrate = CurrentBitrate { shown += it }

    @Test
    fun `the link of the playing track shows its bitrate`() {
        bitrate.onCurrentChanged("a")
        bitrate.onResolved("a", 320)
        assertEquals(320, shown.last())
    }

    @Test
    fun `preloading the next track leaves the bitrate of the playing one`() {
        bitrate.onCurrentChanged("a")
        bitrate.onResolved("a", 320)
        bitrate.onResolved("b", 128)
        assertEquals(320, shown.last())
    }

    @Test
    fun `the bitrate changes exactly when the next track becomes current`() {
        bitrate.onCurrentChanged("a")
        bitrate.onResolved("a", 320)
        bitrate.onResolved("b", 128)
        bitrate.onCurrentChanged("b")
        assertEquals(listOf(0, 320, 128), shown)
    }

    @Test
    fun `a track whose link is not known yet and no track show no bitrate`() {
        bitrate.onCurrentChanged("c")
        assertEquals(0, shown.last())
        bitrate.onResolved("c", 192)
        bitrate.onCurrentChanged(null)
        assertEquals(listOf(0, 192, 0), shown)
    }
}
