package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.support.CARD
import io.github.kickoman.qiyaa.support.PRIMARY
import io.github.kickoman.qiyaa.support.localRow
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPicksTest {
    private val rows =
        listOf(
            localRow(1, "Track 10", "Abbey", 7, 1_010, "Music", "Abbey", file = "10.flac"),
            localRow(2, "Track 2", "Abbey", 7, 1_002, "Music", "Abbey", file = "2.flac"),
            localRow(3, "Disc 2 first", "Abbey", 7, 2_001, "Music", "Abbey", file = "cd2.flac"),
            localRow(4, "Loose", "Singles", 9, 0, "Music", file = "loose.mp3", artist = "Band"),
            localRow(5, "Card", "Singles", 9, 0, "Music", volume = CARD, file = "card.mp3", artist = "Band"),
        )
    private val picks = LocalPicks(rows, NaturalOrder(Locale.ROOT))

    @Test
    fun `LOCAL-11 an album plays by disc and track number`() {
        assertEquals(listOf(2L, 1L, 3L), picks.rowsOf(LocalPick.Album(7)).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 tracks of an album with the same number play by title, not by path`() {
        val album =
            listOf(
                localRow(1, "B", "One", 3, 0, "Music", file = "a.mp3"),
                localRow(2, "A", "One", 3, 0, "Music", file = "b.mp3"),
            )
        val picks = LocalPicks(album, NaturalOrder(Locale.ROOT))
        assertEquals(listOf(2L, 1L), picks.rowsOf(LocalPick.Album(3)).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 a folder takes its subfolders by path, and storages stay apart`() {
        val music = LocalFolder(PRIMARY, listOf("Music"))
        assertEquals(listOf(2L, 1L, 3L, 4L), picks.rowsOf(LocalPick.Folder(music)).map(LocalRow::id))
        val card = LocalFolder(CARD, listOf("Music"))
        assertEquals(listOf(5L), picks.rowsOf(LocalPick.Folder(card)).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 the folders with a file in them, by storage, then by path`() {
        assertEquals(
            listOf(
                LocalFolderPick(LocalFolder(CARD, listOf("Music")), 1),
                LocalFolderPick(LocalFolder(PRIMARY, listOf("Music")), 1),
                LocalFolderPick(LocalFolder(PRIMARY, listOf("Music", "Abbey")), 3),
            ),
            picks.folders,
        )
    }

    @Test
    fun `LOCAL-11 all tracks by path, a folder's name competing with its files' names`() {
        assertEquals(listOf(5L, 2L, 1L, 3L, 4L), picks.rowsOf(LocalPick.All).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 an artist plays by album, then as an album`() {
        val artist =
            listOf(
                localRow(1, "Z2", "Zeta", 11, 2, "Z", file = "1.mp3", artist = "Band"),
                localRow(2, "A1", "Alpha", 12, 1, "A", file = "2.mp3", artist = "Band"),
                localRow(3, "Z1", "Zeta", 11, 1, "Z", file = "3.mp3", artist = "Band"),
                localRow(4, "A2", "Alpha", 12, 2, "A", file = "4.mp3", artist = "Band"),
                localRow(5, "Other", "Alpha", 12, 1, "A", file = "5.mp3", artist = "Other"),
            )
        val picks = LocalPicks(artist, NaturalOrder(Locale.ROOT))
        assertEquals(listOf(2L, 4L, 3L, 1L), picks.rowsOf(LocalPick.Artist("Band")).map(LocalRow::id))
    }

    @Test
    fun `LOCAL-11 albums and artists are listed by name, albums of one name by their id`() {
        assertEquals(listOf("Abbey", "Singles"), picks.albums.map(LocalAlbum::name))
        assertEquals(listOf(LocalArtist("", 3), LocalArtist("Band", 2)), picks.artists)
        val twins =
            listOf(
                localRow(1, "x", "Greatest Hits", 20, 1, "A", file = "1.mp3", artist = "Band"),
                localRow(2, "y", "Greatest Hits", 10, 1, "B", file = "2.mp3", artist = "Band"),
            )
        val picks = LocalPicks(twins, NaturalOrder(Locale.ROOT))
        assertEquals(listOf(10L, 20L), picks.albums.map(LocalAlbum::id))
        assertEquals(listOf(2L, 1L), picks.rowsOf(LocalPick.Artist("Band")).map(LocalRow::id))
    }
}
