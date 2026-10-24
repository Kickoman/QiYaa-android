package io.github.kickoman.qiyaa.local

import io.github.kickoman.qiyaa.track.Track

data class LocalAlbum(val id: Long, val name: String, val trackCount: Int)

data class LocalArtist(val name: String, val trackCount: Int)

data class LocalFolderPick(val folder: LocalFolder, val trackCount: Int)

sealed interface LocalPick {
    data object All : LocalPick

    data class Album(val id: Long) : LocalPick

    data class Artist(val name: String) : LocalPick

    data class Folder(val folder: LocalFolder) : LocalPick
}

class LocalPicks(rows: List<LocalRow>, private val order: NaturalOrder = NaturalOrder()) {
    private val rows = rows.sortedWith(::compareByPath)

    val albums: List<LocalAlbum> =
        this.rows.groupBy { it.albumId }
            .map { (id, tracks) -> LocalAlbum(id, tracks.first().album.orEmpty(), tracks.size) }
            .sortedWith { a, b -> order.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }

    val artists: List<LocalArtist> =
        this.rows.groupBy(::artistOf)
            .map { (name, tracks) -> LocalArtist(name, tracks.size) }
            .sortedWith { a, b -> order.compare(a.name, b.name) }

    val folders: List<LocalFolderPick> =
        this.rows.groupBy { it.folder }
            .map { (folder, tracks) -> LocalFolderPick(folder, tracks.size) }
            .sortedWith { a, b -> compareFolders(a.folder, b.folder) }

    fun tracks(pick: LocalPick): List<Track> = rowsOf(pick).map(LocalTracks::toTrack)

    fun rowsOf(pick: LocalPick): List<LocalRow> = when (pick) {
        LocalPick.All -> rows
        is LocalPick.Album -> rows.filter { it.albumId == pick.id }.sortedWith(::compareInAlbum)
        is LocalPick.Artist -> rows.filter { artistOf(it) == pick.name }.sortedWith(::compareInArtist)
        is LocalPick.Folder -> rows.filter { it.folder.isWithin(pick.folder) }
    }

    private fun compareInArtist(a: LocalRow, b: LocalRow): Int {
        val byAlbum = order.compare(a.album.orEmpty(), b.album.orEmpty())
        if (byAlbum != 0) return byAlbum
        val byAlbumId = a.albumId.compareTo(b.albumId)
        return if (byAlbumId != 0) byAlbumId else compareInAlbum(a, b)
    }

    private fun compareInAlbum(a: LocalRow, b: LocalRow): Int {
        val byNumber = a.trackNumber.compareTo(b.trackNumber)
        if (byNumber != 0) return byNumber
        val byTitle = order.compare(LocalTracks.title(a), LocalTracks.title(b))
        return if (byTitle != 0) byTitle else compareByPath(a, b)
    }

    private fun compareByPath(a: LocalRow, b: LocalRow): Int {
        val byVolume = order.compare(a.folder.volume, b.folder.volume)
        if (byVolume != 0) return byVolume
        val byPath = order.comparePaths(a.folder.path + a.displayName, b.folder.path + b.displayName)
        return if (byPath != 0) byPath else a.id.compareTo(b.id)
    }

    private fun compareFolders(a: LocalFolder, b: LocalFolder): Int {
        val byVolume = order.compare(a.volume, b.volume)
        return if (byVolume != 0) byVolume else order.comparePaths(a.path, b.path)
    }

    private fun artistOf(row: LocalRow): String =
        row.artist?.takeIf { it.isNotBlank() && it != LocalTracks.UNKNOWN }.orEmpty()

    private fun LocalFolder.isWithin(other: LocalFolder): Boolean =
        volume == other.volume && path.size >= other.path.size &&
            path.subList(0, other.path.size) == other.path
}
