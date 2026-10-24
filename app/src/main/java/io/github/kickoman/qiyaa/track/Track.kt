package io.github.kickoman.qiyaa.track

enum class TrackSource(val key: String) {
    YANDEX("yandex"),
    LOCAL("local"),
    ;

    companion object {
        fun fromKey(key: String): TrackSource? = entries.firstOrNull { it.key == key }
    }
}

data class TrackKey(val source: TrackSource, val id: String)

data class Track(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val albumId: String = "",
    val durationMs: Long = 0,
    val available: Boolean = true,
    val coverUrl: String? = null,
    val source: TrackSource = TrackSource.YANDEX,
    val bitrateKbps: Int = 0,
) {
    val key: TrackKey get() = TrackKey(source, id)

    val isYandex: Boolean get() = when (source) {
        TrackSource.YANDEX -> true
        TrackSource.LOCAL -> false
    }

    val artistLine: String get() = artists.joinToString(", ")

    val displayTitle: String
        get() = if (artists.isEmpty()) title else "$artistLine - $title"
}
