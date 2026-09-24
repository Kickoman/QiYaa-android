package io.github.kickoman.qiyaa.yandex

data class Account(val uid: String = "", val login: String = "", val displayName: String = "") {
    val isValid: Boolean get() = uid.isNotEmpty()
}

data class Track(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val albumId: String = "",
    val durationMs: Long = 0,
    val available: Boolean = true,
    /** Full https URL of a 400×400 cover, or null. Not present in the desktop app. */
    val coverUrl: String? = null,
) {
    val artistLine: String get() = artists.joinToString(", ")

    /** "A, B - Title" (as the desktop app's marquee). */
    val displayTitle: String
        get() = if (artists.isEmpty()) title else "$artistLine - $title"

    val webUrl: String
        get() = if (albumId.isEmpty()) "https://music.yandex.ru/track/$id"
        else "https://music.yandex.ru/album/$albumId/track/$id"
}

/** Artist or album reference. */
data class NamedRef(val id: String, val name: String)

data class PlaylistRef(val ownerUid: String, val kind: String, val title: String, val trackCount: Int)

/** Rotor station; [id] is "type:tag", e.g. "genre:rock" or "user:onyourwave". */
data class Station(val id: String, val type: String, val name: String)

data class WaveBatch(val sessionId: String = "", val batchId: String = "", val tracks: List<Track> = emptyList())

data class SearchResult(
    val bestType: String = "",
    val bestId: String = "",
    val bestName: String = "",
    val tracks: List<Track> = emptyList(),
)

data class DownloadVariant(val codec: String, val bitrateKbps: Int, val preview: Boolean, val downloadInfoUrl: String)

data class DownloadInfo(val host: String, val path: String, val ts: String, val s: String)

data class ResolvedUrl(val url: String, val bitrateKbps: Int)

class ApiException(message: String) : java.io.IOException(message)
