package io.github.kickoman.qiyaa.data

enum class AudioQuality(val key: String) {
    STANDARD("standard"),
    ECONOMY("economy"),
    ;

    companion object {
        val DEFAULT = STANDARD

        fun fromKey(key: String?): AudioQuality = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
