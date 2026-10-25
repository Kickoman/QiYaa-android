package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPagesTest {
    @Test
    fun `a Yandex track's page is its album's track page, or the bare track page without an album`() {
        assertEquals(
            "https://music.yandex.ru/album/5307396/track/38633712",
            Track(id = "38633712", title = "A", albumId = "5307396").webUrl,
        )
        assertEquals("https://music.yandex.ru/track/38633712", Track(id = "38633712", title = "A").webUrl)
    }

    @Test
    fun `SPOT-19 a Spotify track's page is open spotify com, whatever its album`() {
        val spotify =
            Track(
                id = "6PCoZdjSPbRMehHviTrpB3",
                title = "S",
                albumId = "2noRn2Aes5aoNVsU6iWThc",
                source = TrackSource.SPOTIFY,
            )
        assertEquals("https://open.spotify.com/track/6PCoZdjSPbRMehHviTrpB3", spotify.webUrl)
        assertEquals(spotify.webUrl, spotify.copy(albumId = "").webUrl)
    }

    @Test
    fun `LOCAL-08 a local file has no page`() {
        assertNull(Track(id = "42", title = "L", albumId = "7", source = TrackSource.LOCAL).webUrl)
    }
}
