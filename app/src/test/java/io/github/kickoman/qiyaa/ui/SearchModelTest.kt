package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.spotify.SpotifyFound
import io.github.kickoman.qiyaa.spotify.SpotifyFoundAlbum
import io.github.kickoman.qiyaa.spotify.SpotifyItem
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.yandex.FoundAlbum
import io.github.kickoman.qiyaa.yandex.NamedRef
import io.github.kickoman.qiyaa.yandex.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchModelTest {
    @Test
    fun `SRC-13 the best result is shown once, the parts leave out the entry with its id`() {
        val best = yandex("1")
        val found =
            Found.of(
                SearchResult(
                    bestType = "track",
                    bestId = "1",
                    bestName = "One",
                    tracks = listOf(yandex("2"), best, yandex("3")),
                    bestTrack = best,
                    artists = listOf(NamedRef("a1", "Band")),
                ),
            )
        assertEquals(best, found.bestTrack)
        assertEquals(listOf("2", "3"), found.tracks.map(Track::id))
        assertEquals(listOf(best, yandex("2"), yandex("3")), found.playable())

        val album =
            Found.of(
                SearchResult(
                    bestType = "album",
                    bestId = "9",
                    bestName = "Album",
                    albums = listOf(FoundAlbum("8", "Other"), FoundAlbum("9", "Album", listOf("Band"), 1989)),
                    artists = listOf(NamedRef("9", "An artist with the album's id")),
                ),
            )
        assertEquals(FoundPick(FoundPick.Kind.ALBUM, "9", "Album", listOf("Band"), 1989), album.bestAlbum)
        assertEquals(listOf("8"), album.albums.map(FoundPick::id))
        assertEquals(listOf("9"), album.artists.map(FoundPick::id))
    }

    @Test
    fun `SRC-13 an unavailable track is not shown, the best one neither`() {
        val best = yandex("1", available = false)
        val found =
            Found.of(
                SearchResult(
                    bestType = "track",
                    bestId = "1",
                    tracks = listOf(best, yandex("2", available = false), yandex("3")),
                    bestTrack = best,
                ),
            )
        assertNull(found.bestTrack)
        assertEquals(listOf("3"), found.tracks.map(Track::id))
        assertEquals(listOf("3"), found.playable().map(Track::id))
    }

    @Test
    fun `SRC-13 a best playlist, a best podcast or a best result with no id is not shown`() {
        for (type in listOf("playlist", "other", "")) {
            val found = Found.of(SearchResult(bestType = type, bestId = "5", bestName = "Five"))
            assertTrue(type, found.isEmpty)
        }
        val noId = Found.of(SearchResult(bestType = "artist", bestId = "", bestName = "Nobody"))
        assertNull(noId.bestArtist)
        val noTrackId = Found.of(SearchResult(bestType = "track", bestTrack = yandex("")))
        assertNull(noTrackId.bestTrack)
    }

    @Test
    fun `SRC-13 a best artist is shown with its name, and a best album without an entry in the albums too`() {
        val artist = Found.of(SearchResult(bestType = "artist", bestId = "7", bestName = "Кино"))
        assertEquals(FoundPick(FoundPick.Kind.ARTIST, "7", "Кино"), artist.bestArtist)
        assertFalse(artist.isEmpty)
        val album = Found.of(SearchResult(bestType = "album", bestId = "4", bestName = "Группа крови"))
        assertEquals(FoundPick(FoundPick.Kind.ALBUM, "4", "Группа крови"), album.bestAlbum)
    }

    @Test
    fun `SRC-19 a reply with nothing to show is empty`() {
        assertTrue(Found.of(SearchResult()).isEmpty)
        assertTrue(Found.of(SearchResult(tracks = listOf(yandex("1", available = false)))).isEmpty)
        assertFalse(Found.of(SearchResult(albums = listOf(FoundAlbum("1", "One")))).isEmpty)
    }

    @Test
    fun `SPOT-11 a best track is the found track with its URI, artists and albums by URI`() {
        val found =
            Found.of(
                SpotifyFound(
                    kind = SpotifyFound.Kind.TRACK,
                    best = SpotifyItem("spotify:track:b", "Hit"),
                    trackUris = listOf("spotify:track:a", "spotify:track:b"),
                    artists = listOf(SpotifyItem("spotify:artist:x", "Band")),
                    albums = listOf(
                        SpotifyFoundAlbum("spotify:album:y", "Double", listOf("Band"), null, true),
                    ),
                ),
                listOf(spotify("a"), spotify("b")),
            )
        assertEquals(spotify("b"), found.bestTrack)
        assertEquals(listOf(spotify("a")), found.tracks)
        assertEquals(listOf(FoundPick(FoundPick.Kind.ARTIST, "spotify:artist:x", "Band")), found.artists)
        assertEquals(
            listOf(FoundPick(FoundPick.Kind.ALBUM, "spotify:album:y", "Double", listOf("Band"), null, true)),
            found.albums,
        )
    }

    @Test
    fun `SPOT-11 a best album takes its entry, a best artist leaves the part, a lost best track is none`() {
        val album =
            Found.of(
                SpotifyFound(
                    kind = SpotifyFound.Kind.ALBUM,
                    best = SpotifyItem("spotify:album:y", "Double"),
                    albums = listOf(SpotifyFoundAlbum("spotify:album:y", "Double", listOf("Band"), 1991)),
                ),
                emptyList(),
            )
        assertEquals(
            FoundPick(FoundPick.Kind.ALBUM, "spotify:album:y", "Double", listOf("Band"), 1991),
            album.bestAlbum,
        )
        assertTrue(album.albums.isEmpty())
        val artist =
            Found.of(
                SpotifyFound(
                    kind = SpotifyFound.Kind.ARTIST,
                    best = SpotifyItem("spotify:artist:x", "Band"),
                    artists = listOf(
                        SpotifyItem("spotify:artist:x", "Band"),
                        SpotifyItem("spotify:artist:z", "Z"),
                    ),
                ),
                emptyList(),
            )
        assertEquals(listOf("spotify:artist:z"), artist.artists.map(FoundPick::id))
        val lost =
            Found.of(
                SpotifyFound(
                    SpotifyFound.Kind.TRACK,
                    SpotifyItem("spotify:track:q", "Gone"),
                    listOf("spotify:track:q"),
                ),
                listOf(spotify("a")),
            )
        assertNull(lost.bestTrack)
        assertEquals(listOf(spotify("a")), lost.tracks)
    }

    @Test
    fun `SPOT-11 the metadata asked is the found tracks, with a best track missing from them first`() {
        val uris = listOf("spotify:track:a", "spotify:track:b")
        val lost = SpotifyFound(SpotifyFound.Kind.TRACK, SpotifyItem("spotify:track:x", "X"), uris)
        assertEquals(listOf("spotify:track:x") + uris, Found.metadataUris(lost))
        val among = SpotifyFound(SpotifyFound.Kind.TRACK, SpotifyItem("spotify:track:b", "B"), uris)
        assertEquals(uris, Found.metadataUris(among))
        val artist = SpotifyFound(SpotifyFound.Kind.ARTIST, SpotifyItem("spotify:artist:z", "Z"), uris)
        assertEquals(uris, Found.metadataUris(artist))
    }

    @Test
    fun `SRC-13 a part shows its first few until it is expanded`() {
        val items = (1..8).toList()
        assertEquals(listOf(1, 2, 3, 4, 5, 6), shownPart(SearchPart.TRACKS, items, emptySet()))
        assertEquals(listOf(1, 2, 3), shownPart(SearchPart.ALBUMS, items, setOf(SearchPart.TRACKS)))
        assertEquals(items, shownPart(SearchPart.ALBUMS, items, setOf(SearchPart.ALBUMS)))
    }

    private fun yandex(id: String, available: Boolean = true) = Track(id, "Track $id", available = available)

    private fun spotify(id: String) = Track(id, "Track $id", source = TrackSource.SPOTIFY)
}
