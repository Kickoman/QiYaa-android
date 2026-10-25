package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.jam.Add
import io.github.kickoman.qiyaa.jam.Decoded
import io.github.kickoman.qiyaa.jam.JamCodec
import io.github.kickoman.qiyaa.jam.JamGuest
import io.github.kickoman.qiyaa.jam.JamTrack
import io.github.kickoman.qiyaa.jam.JamTrackSource
import io.github.kickoman.qiyaa.jam.NowPlayingSource
import io.github.kickoman.qiyaa.jam.Playing
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.TrackParsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `GUEST-07 a guest's add of the longest toJam track, in 3-byte characters, fits the 4 KiB frame`() {
        val cjk = "\u6F22"
        val track =
            Track(
                id = "9".repeat(64),
                title = cjk.repeat(200),
                artists = List(12) { cjk.repeat(70) },
                albumId = "8".repeat(64),
                durationMs = Long.MAX_VALUE,
                coverUrl = TrackParsing.coverUrl(cjk.repeat(298) + "%%"),
            )
        val jam = JamTracks.toJam(track)!!
        assertEquals(cjk.repeat(150), jam.title)
        assertEquals(List(10) { cjk.repeat(64) }, jam.artists)
        assertEquals(cjk.repeat(298) + "%%", jam.coverUri)
        val frame = JamCodec.encode(Add("a".repeat(36), track = jam))
        assertTrue(JamCodec.decodeClient(frame) is Decoded.Message)
        val bytes = frame.toByteArray(Charsets.UTF_8).size
        assertTrue("$bytes bytes", bytes in 3_500..JamGuest.GUEST_FRAME_BYTES)
    }

    @Test
    fun `a cover URL with white space in it gives no cover, and the track still passes the codec`() {
        for (space in listOf(" ", "\u00A0", "\uFEFF")) {
            val coverUrl = "https://" + template.replace("/%%", "$space/400x400")
            assertNull(JamTracks.coverUri(coverUrl))
            val track = JamTracks.toJam(
                Track(id = "1", title = "Песня", artists = listOf("Гурт"), coverUrl = coverUrl),
            )
            assertNull(track?.coverUri)
            assertTrue(JamCodec.decodeClient(JamCodec.encode(Add("q1", track = track))) is Decoded.Message)
        }
    }

    @Test
    fun `HOST-37 a local base track is the host's own track, with title, artists and length only`() {
        val local =
            Track(
                id = "42",
                title = "Свой\u0007файл",
                artists = listOf("Гурт", " "),
                albumId = "5307396",
                durationMs = 180_000,
                coverUrl = "https://$template",
                source = TrackSource.LOCAL,
            )
        val track = JamTracks.baseTrack(local, HOST_SECRET)
        assertEquals(
            JamTrack(
                id = "654c01b48d75abaf",
                title = "Свой файл",
                artists = listOf("Гурт"),
                durationMs = 180_000,
                source = JamTrackSource.HOST,
            ),
            track,
        )
        assertTrue(JamCodec.decodeClient(JamCodec.encode(Add("q1", track = track))) is Decoded.Message)
    }

    @Test
    fun `HOST-37 the host's id is 16 hex digits of HMAC-SHA256 of the source and id, keyed by the secret`() {
        val first = Track(id = "/storage/emulated/0/Music/a.mp3", title = "A", source = TrackSource.LOCAL)
        val second = first.copy(id = "/storage/emulated/0/Music/b.mp3")
        val id = JamTracks.baseTrack(first, HOST_SECRET)!!.id
        assertTrue(id, Regex("^[0-9a-f]{16}$").matches(id))
        assertEquals(id, JamTracks.baseTrack(first.copy(title = "Renamed"), HOST_SECRET)?.id)
        assertTrue(id != JamTracks.baseTrack(second, HOST_SECRET)?.id)
        assertTrue(id != JamTracks.baseTrack(first, OTHER_SECRET)?.id)
        assertEquals("654c01b48d75abaf", JamTracks.baseTrack(first.copy(id = "42"), HOST_SECRET)?.id)
    }

    @Test
    fun `HOST-37 a local track is cut to the limits of a track`() {
        val local =
            Track(
                id = "42",
                title = "樂".repeat(200),
                artists = List(12) { "藝".repeat(70) } + "",
                durationMs = Long.MAX_VALUE,
                source = TrackSource.LOCAL,
            )
        val track = JamTracks.baseTrack(local, HOST_SECRET)!!
        assertEquals("樂".repeat(150), track.title)
        assertEquals(List(10) { "藝".repeat(64) }, track.artists)
        assertEquals(86_400_000L, track.durationMs)
        val playing = Playing(NowPlayingSource.WAVE, track = track, positionMs = 0, paused = false)
        assertTrue(JamCodec.decodeClient(JamCodec.encode(playing)) is Decoded.Message)
    }

    @Test
    fun `HOST-37 a local track without a title after cleaning is not reported`() {
        val local = Track(id = "42", title = " \u0001 ", source = TrackSource.LOCAL)
        assertNull(JamTracks.baseTrack(local, HOST_SECRET))
    }

    @Test
    fun `HOST-10 a Yandex base track is toJam's, and one the protocol cannot carry stays out`() {
        val yandex = Track(id = "38633712", title = "Песня", artists = listOf("Гурт"), albumId = "5307396")
        assertEquals(JamTracks.toJam(yandex), JamTracks.baseTrack(yandex, HOST_SECRET))
        assertNull(JamTracks.baseTrack(yandex.copy(id = "a b"), HOST_SECRET))
    }

    @Test
    fun `HOST-20 ROOM-66 a local track with an id like the catalog's is never a catalog track`() {
        assertNull(JamTracks.toJam(Track(id = "42", title = "Свой файл", source = TrackSource.LOCAL)))
    }

    @Test
    fun `HOST-20 ROOM-66 a Spotify track, whose id passes the catalog's check, is never a catalog track`() {
        assertNull(JamTracks.toJam(Track(id = SPOTIFY_ID, title = "Песня", source = TrackSource.SPOTIFY)))
    }

    @Test
    fun `HOST-37 a Spotify base track is the host's own track, never its Spotify id or a cover`() {
        val spotify =
            Track(
                id = SPOTIFY_ID,
                title = "Песня",
                artists = listOf("Гурт"),
                albumId = "2noRn2Aes5aoNVsU6iWThc",
                durationMs = 180_000,
                coverUrl = "https://i.scdn.co/image/ab67616d00004851e8b066f70c206551210d902b",
                source = TrackSource.SPOTIFY,
            )
        val track = JamTracks.baseTrack(spotify, HOST_SECRET)
        assertEquals(
            JamTrack(
                id = "e30eec89bbabe228",
                title = "Песня",
                artists = listOf("Гурт"),
                durationMs = 180_000,
                source = JamTrackSource.HOST,
            ),
            track,
        )
        val playing = Playing(NowPlayingSource.WAVE, track = track, positionMs = 0, paused = false)
        assertTrue(JamCodec.decodeClient(JamCodec.encode(playing)) is Decoded.Message)
        val sameId = Track(id = "42", title = "A")
        val ids = listOf(TrackSource.SPOTIFY, TrackSource.LOCAL).map {
            JamTracks.baseTrack(sameId.copy(source = it), HOST_SECRET)?.id
        }
        assertEquals(listOf("292039a693c36956", "654c01b48d75abaf"), ids)
    }

    @Test
    fun `a cover URL without a size template gives no cover`() {
        assertEquals(template, JamTracks.coverUri("https://" + template.replace("%%", "400x400")))
        assertNull(JamTracks.coverUri("https://example.org/cover.jpg"))
        assertNull(JamTracks.coverUri(null))
    }

    private companion object {
        const val HOST_SECRET = "R0CuY0ewFywBJU_1W65a_1GZ9ERuf21kPUAYWz9HUUU"
        const val OTHER_SECRET = "Kq8Vb2nT5xWm9Lp3Rz7Yc1Hd6Gf4Js0Ne8Ua2Io5Pk3"
        const val SPOTIFY_ID = "6PCoZdjSPbRMehHviTrpB3"
    }
}
