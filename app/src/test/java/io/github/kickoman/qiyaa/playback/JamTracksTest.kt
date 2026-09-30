package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.jam.JamTrack
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.TrackParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JamTracksTest {
    private val template = "avatars.yandex.net/get-music-content/95061/4f3808a0.a.5307396-3/%%"

    @Test
    fun `a track goes to the protocol with its cover template, and comes back with the same cover URL`() {
        val track =
            Track(
                id = "38633712",
                title = "Группа крови",
                artists = listOf("КИНО"),
                albumId = "5307396",
                durationMs = 235_100,
                coverUrl = TrackParsing.coverUrl(template),
            )
        val jam = JamTracks.toJam(track)
        assertEquals(JamTrack("38633712", "5307396", "Группа крови", listOf("КИНО"), 235_100, template), jam)
        assertEquals(track, JamTracks.fromJam(jam!!))
    }

    @Test
    fun `artists are cut to 10 names of 64 characters, and control characters become spaces`() {
        val long = "Я".repeat(70)
        val track = Track(id = "1", title = "A\tB", artists = List(12) { if (it == 0) long else "N$it" } + "")
        val jam = JamTracks.toJam(track)!!
        assertEquals("A B", jam.title)
        assertEquals(10, jam.artists.size)
        assertEquals("Я".repeat(64), jam.artists[0])
        assertEquals("N9", jam.artists[9])
    }

    @Test
    fun `a track the protocol cannot carry is left out`() {
        assertNull(JamTracks.toJam(Track(id = "1:2", title = "A")))
        assertNull(JamTracks.toJam(Track(id = "1", title = " ")))
        assertNull(JamTracks.toJam(Track(id = "1", title = "A", albumId = "x y"))?.albumId)
    }

    @Test
    fun `a cover URL without a size template gives no cover`() {
        assertEquals(template, JamTracks.coverUri("https://" + template.replace("%%", "400x400")))
        assertNull(JamTracks.coverUri("https://example.org/cover.jpg"))
        assertNull(JamTracks.coverUri(null))
    }
}
