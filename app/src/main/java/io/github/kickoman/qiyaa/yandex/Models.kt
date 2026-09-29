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
    val coverUrl: String? = null,
) {
    val artistLine: String get() = artists.joinToString(", ")

    val displayTitle: String
        get() = if (artists.isEmpty()) title else "$artistLine - $title"

    val webUrl: String
        get() =
            if (albumId.isEmpty()) {
                "https://music.yandex.ru/track/$id"
            } else {
                "https://music.yandex.ru/album/$albumId/track/$id"
            }
}

data class NamedRef(val id: String, val name: String)

data class PlaylistRef(val ownerUid: String, val kind: String, val title: String, val trackCount: Int)

data class Station(val id: String, val type: String, val name: String)

data class WaveBatch(
    val sessionId: String = "",
    val batchId: String = "",
    val tracks: List<Track> = emptyList(),
)

data class SearchResult(
    val bestType: String = "",
    val bestId: String = "",
    val bestName: String = "",
    val tracks: List<Track> = emptyList(),
)

data class DownloadVariant(
    val codec: String,
    val bitrateKbps: Int,
    val preview: Boolean,
    val downloadInfoUrl: String,
)

data class DownloadInfo(val host: String, val path: String, val timestamp: String, val secret: String)

data class ResolvedUrl(val url: String, val bitrateKbps: Int)

enum class WaveEvent(val wireName: String) {
    RADIO_STARTED("radioStarted"),
    TRACK_STARTED("trackStarted"),
    TRACK_FINISHED("trackFinished"),
    SKIP("skip"),
}

data class WaveContext(val sessionId: String, val stationId: String, val batchId: String)
