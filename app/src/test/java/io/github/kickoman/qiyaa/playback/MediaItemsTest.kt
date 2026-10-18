package io.github.kickoman.qiyaa.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaItemsTest {
    @Test
    fun `QUAL-05 a revised track URI is the plain one with the revision as a query`() {
        assertEquals("qiyaa://track/42", MediaItems.uriText("42"))
        assertEquals("qiyaa://track/42", MediaItems.uriText("42", revision = 0))
        assertEquals("qiyaa://track/42?r=3", MediaItems.uriText("42", revision = 3))
    }

    @Test
    fun `a negative revision is refused with the track and the value`() {
        val failed = assertThrows(IllegalArgumentException::class.java) { MediaItems.uriText("42", -1) }
        assertEquals("The revision of track 42 is -1", failed.message)
    }
}
