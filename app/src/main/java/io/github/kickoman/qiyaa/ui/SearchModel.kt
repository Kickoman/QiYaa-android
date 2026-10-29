package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.queue.AddPlacement
import io.github.kickoman.qiyaa.spotify.SpotifyFound
import io.github.kickoman.qiyaa.spotify.SpotifyFoundAlbum
import io.github.kickoman.qiyaa.spotify.SpotifyItem
import io.github.kickoman.qiyaa.spotify.SpotifyTracks
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackKey
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.FoundAlbum
import io.github.kickoman.qiyaa.yandex.SearchResult

data class FoundPick(
    val kind: Kind,
    val id: String,
    val name: String,
    val artists: List<String> = emptyList(),
    val year: Int? = null,
    val single: Boolean = false,
) {
    enum class Kind { ARTIST, ALBUM }
}

data class Found(
    val bestTrack: Track? = null,
    val bestArtist: FoundPick? = null,
    val bestAlbum: FoundPick? = null,
    val tracks: List<Track> = emptyList(),
    val artists: List<FoundPick> = emptyList(),
    val albums: List<FoundPick> = emptyList(),
) {
    val isEmpty: Boolean
        get() = bestTrack == null &&
            bestArtist == null &&
            bestAlbum == null &&
            tracks.isEmpty() &&
            artists.isEmpty() &&
            albums.isEmpty()

    fun shown(): Found {
        val best = bestTrack?.takeIf { it.available }
        return copy(
            bestTrack = best,
            tracks = tracks.filter { it.available && it.key != best?.key },
            artists = artists.filter { it.id != bestArtist?.id },
            albums = albums.filter { it.id != bestAlbum?.id },
        )
    }

    fun playable(): List<Track> = listOfNotNull(bestTrack) + tracks

    companion object {
        fun of(result: SearchResult): Found {
            val hasBest = result.bestId.isNotEmpty()
            val albums = result.albums.map(::albumPick)
            return Found(
                bestTrack = result.bestTrack?.takeIf { hasBest },
                bestArtist = FoundPick(FoundPick.Kind.ARTIST, result.bestId, result.bestName)
                    .takeIf { hasBest && result.bestType == BEST_ARTIST },
                bestAlbum = if (hasBest && result.bestType == BEST_ALBUM) {
                    albums.firstOrNull { it.id == result.bestId }
                        ?: FoundPick(FoundPick.Kind.ALBUM, result.bestId, result.bestName)
                } else {
                    null
                },
                tracks = result.tracks,
                artists = result.artists.map { FoundPick(FoundPick.Kind.ARTIST, it.id, it.name) },
                albums = albums,
            ).shown()
        }

        fun of(found: SpotifyFound, tracks: List<Track>): Found {
            val bestUri = found.best?.uri.takeIf { found.kind == SpotifyFound.Kind.TRACK }
            val bestTrack = bestUri?.let { uri -> tracks.firstOrNull { SpotifyTracks.uriOf(it) == uri } }
            val albums = found.albums.map(::albumPick)
            val best = found.best
            return Found(
                bestTrack = bestTrack,
                bestArtist = best?.takeIf { found.kind == SpotifyFound.Kind.ARTIST }
                    ?.let { pick(FoundPick.Kind.ARTIST, it) },
                bestAlbum = best?.takeIf { found.kind == SpotifyFound.Kind.ALBUM }?.let { item ->
                    albums.firstOrNull { it.id == item.uri } ?: pick(FoundPick.Kind.ALBUM, item)
                },
                tracks = tracks,
                artists = found.artists.map { pick(FoundPick.Kind.ARTIST, it) },
                albums = albums,
            ).shown()
        }

        fun metadataUris(found: SpotifyFound): List<String> {
            val best = found.best?.uri?.takeIf {
                found.kind == SpotifyFound.Kind.TRACK &&
                    it !in found.trackUris
            }
            return listOfNotNull(best) + found.trackUris
        }

        private fun albumPick(album: FoundAlbum): FoundPick =
            FoundPick(FoundPick.Kind.ALBUM, album.id, album.title, album.artists, album.year, album.single)

        private fun albumPick(album: SpotifyFoundAlbum): FoundPick =
            FoundPick(FoundPick.Kind.ALBUM, album.uri, album.name, album.artists, album.year, album.single)

        private fun pick(kind: FoundPick.Kind, item: SpotifyItem): FoundPick =
            FoundPick(kind, item.uri, item.name)

        private const val BEST_ARTIST = "artist"
        private const val BEST_ALBUM = "album"
    }
}

enum class SearchSource { YANDEX, SPOTIFY }

enum class SearchPart(val first: Int) { BEST(1), TRACKS(6), ARTISTS(3), ALBUMS(3) }

sealed interface SearchPhase {
    data object Idle : SearchPhase

    data object Searching : SearchPhase

    data class Failed(val error: ErrorKind) : SearchPhase

    data object NothingFound : SearchPhase

    data class Results(val found: Found) : SearchPhase
}

data class SearchOpened(val pick: FoundPick, val tracks: ListState<Track> = ListState.Loading)

sealed interface SearchTarget {
    data class OfTrack(val track: Track) : SearchTarget

    data class OfAlbum(val album: FoundPick) : SearchTarget
}

data class SearchJam(val connected: Boolean = false, val waiting: Map<String, String> = emptyMap())

data class SearchUi(
    val source: SearchSource = SearchSource.YANDEX,
    val text: String = "",
    val query: String = "",
    val phase: SearchPhase = SearchPhase.Idle,
    val expanded: Set<SearchPart> = emptySet(),
    val opened: SearchOpened? = null,
    val added: Map<TrackKey, AddPlacement> = emptyMap(),
    val adding: SearchTarget? = null,
    val jam: SearchJam? = null,
    val sending: Set<String> = emptySet(),
    val refused: Map<String, String> = emptyMap(),
)

fun <T> shownPart(part: SearchPart, items: List<T>, expanded: Set<SearchPart>): List<T> =
    if (part in expanded) items else items.take(part.first)
