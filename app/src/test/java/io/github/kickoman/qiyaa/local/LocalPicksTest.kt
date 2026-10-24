package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.track.TrackSource
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPicksTest {
    private val rows =
        listOf(
            row(1, "Track 10", "Abbey", 7, 1_010, "Music", "Abbey", file = "10.flac"),
            row(2, "Track 2", "Abbey", 7, 1_002, "Music", "Abbey", file = "2.flac"),
            row(3, "Disc 2 first", "Abbey", 7, 2_001, "Music", "Abbey", file = "cd2.flac"),
            row(4, "Loose", "Singles", 9, 0, "Music", file = "loose.mp3", artist = "Band"),
            row(5, "Card", "Singles", 9, 0, "Music", volume = CARD, file = "card.mp3", artist = "Band"),
        )
    private val picks = LocalPicks(rows, NaturalOrder(Locale.ROOT))

    @Test
    fun `LOCAL-11 an album plays by disc and track number`() {
        assertEquals(listOf(2L, 1L, 3L), picks.rowsOf(LocalPick.Album(7)).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 a folder takes its subfolders, and storages stay apart`() {
        val music = LocalFolder("external_primary", listOf("Music"))
        assertEquals(listOf(1L, 2L, 3L, 4L), picks.rowsOf(LocalPick.Folder(music)).map(LocalRow::id).sorted())
        val card = LocalFolder(CARD, listOf("Music"))
        assertEquals(listOf(5L), picks.rowsOf(LocalPick.Folder(card)).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 all tracks by path, a folder's name competing with its files' names`() {
        assertEquals(listOf(5L, 2L, 1L, 3L, 4L), picks.rowsOf(LocalPick.All).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 an artist plays by album, then as an album, and the lists are by name`() {
        assertEquals(listOf(4L, 5L), picks.rowsOf(LocalPick.Artist("Band")).map(LocalRow::id).sorted())
        assertEquals(listOf("Abbey", "Singles"), picks.albums.map(LocalAlbum::name))
        assertEquals(listOf("", "Band"), picks.artists.map(LocalArtist::name))
        assertEquals(3, picks.folders.size)
    }

    @Test
    fun `LOCAL-04 LOCAL-05 an untagged row is titled by its file name and has the library's cover`() {
        val untagged = row(42, null, "A", 1, 0, "Music", file = "Song name.mp3", durationMs = MINUTE)
        val track = LocalTracks.toTrack(untagged.copy(sizeBytes = 2_400_000))
        assertEquals("Song name", track.title)
        assertEquals(emptyList<String>(), track.artists)
        assertEquals(TrackSource.LOCAL, track.source)
        assertEquals("content://media/external/audio/media/42/albumart", track.coverUrl)
        assertEquals(320, track.bitrateKbps)
    }

    @Test
    fun `LOCAL-13 the formats taken, and FLAC only with a decoder`() {
        assertTrue(LocalTracks.isTaken("audio/mpeg", flacDecodes = false))
        assertTrue(LocalTracks.isTaken("application/ogg", flacDecodes = false))
        assertTrue(LocalTracks.isTaken("audio/flac", flacDecodes = true))
        assertFalse(LocalTracks.isTaken("audio/flac", flacDecodes = false))
        assertFalse(LocalTracks.isTaken("audio/aac-adts", flacDecodes = true))
        assertFalse(LocalTracks.isTaken(null, flacDecodes = true))
    }

    @Test
    fun `LOCAL-14 a saved track the library now gives another title or length means a renumbered library`() {
        val saved = listOf(LocalTracks.toTrack(rows[0]), LocalTracks.toTrack(rows[1]))
        assertEquals(listOf(1L, 2L), LocalCheck.localIds(saved))
        assertFalse(LocalCheck.renumbered(saved, mapOf(1L to rows[0], 2L to rows[1])))
        assertFalse(LocalCheck.renumbered(saved, mapOf(1L to rows[0])))
        assertTrue(LocalCheck.renumbered(saved, mapOf(1L to rows[0], 2L to rows[3].copy(id = 2))))
    }

    private fun row(
        id: Long,
        title: String?,
        album: String,
        albumId: Long,
        trackNumber: Int,
        vararg path: String,
        volume: String = "external_primary",
        file: String,
        artist: String? = LocalTracks.UNKNOWN,
        sizeBytes: Long = 1_000_000,
        durationMs: Long = 100_000,
    ) = LocalRow(
        id = id,
        title = title,
        displayName = file,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        trackNumber = trackNumber,
        mimeType = "audio/flac",
        sizeBytes = sizeBytes,
        bitrateBitsPerSecond = null,
        folder = LocalFolder(volume, path.toList()),
    )

    private companion object {
        const val CARD = "1234-ABCD"
        const val MINUTE = 60_000L
    }
}
