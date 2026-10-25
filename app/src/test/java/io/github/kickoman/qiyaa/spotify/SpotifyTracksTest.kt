package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AlbumRef
import io.github.kickoman.qiyaa.spotify.protocol.ArtistRef
import io.github.kickoman.qiyaa.spotify.protocol.AudioFileRef
import io.github.kickoman.qiyaa.spotify.protocol.AudioFormat
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Image
import io.github.kickoman.qiyaa.spotify.protocol.SpotifyTrack
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyTracksTest {
    @Test
    fun `SPOT-19 the cover is the smallest at least as large as shown, else the largest`() {
        val covers = listOf(image(0x64, 64), image(0x30, 300), image(0x40, 640))
        assertEquals(url(0x64), SpotifyTracks.coverUrlOf(covers, 50))
        assertEquals(url(0x64), SpotifyTracks.coverUrlOf(covers, 64))
        assertEquals(url(0x30), SpotifyTracks.coverUrlOf(covers, 200))
        assertEquals(url(0x40), SpotifyTracks.coverUrlOf(covers, 400))
        assertEquals(url(0x40), SpotifyTracks.coverUrlOf(covers, 2000))
        assertEquals(url(0x40), SpotifyTracks.coverUrlOf(covers.reversed(), 2000))
        assertNull(SpotifyTracks.coverUrlOf(emptyList(), 400))
    }

    @Test
    fun `SPOT-19 among covers of one size the first wins`() {
        assertEquals(url(0x01), SpotifyTracks.coverUrlOf(listOf(image(0x01, 640), image(0x02, 640)), 400))
        assertEquals(url(0x01), SpotifyTracks.coverUrlOf(listOf(image(0x01, 300), image(0x02, 300)), 400))
    }

    @Test
    fun `a Spotify track is a Track of the source spotify with its base62 id and no album id`() {
        val gid = Bytes.of(ByteArray(16) { 1 })
        val track =
            SpotifyTrack(
                gid = gid,
                name = "Plays",
                artists = listOf(ArtistRef(name = "Band"), ArtistRef(name = "Guest")),
                album = AlbumRef(name = "Album", year = 1991, covers = listOf(image(0x40, 640))),
                durationMs = 200_000,
                files = listOf(AudioFileRef(Bytes.of(ByteArray(20) { 3 }), AudioFormat.OGG_VORBIS_96)),
            )
        assertEquals(
            Track(
                id = "01TrY6CZi6JaolPzVQ3HW1",
                title = "Plays",
                artists = listOf("Band", "Guest"),
                albumId = "",
                durationMs = 200_000,
                available = true,
                coverUrl = url(0x40),
                source = TrackSource.SPOTIFY,
            ),
            SpotifyTracks.trackOf(track),
        )
        val mp3Only = track.copy(files = listOf(AudioFileRef(Bytes.of(ByteArray(20)), AudioFormat(4))))
        assertEquals(false, SpotifyTracks.trackOf(mp3Only).available)
        assertEquals(true, SpotifyTracks.trackOf(mp3Only.copy(alternatives = listOf(track))).available)
    }

    private fun image(mark: Int, pixels: Int): Image = Image(
        Bytes.of(
            ByteArray(20) {
                mark.toByte()
            },
        ),
        pixels,
    )

    private fun url(mark: Int): String = SpotifyTracks.COVER_HOST + "%02x".format(mark).repeat(20)
}
