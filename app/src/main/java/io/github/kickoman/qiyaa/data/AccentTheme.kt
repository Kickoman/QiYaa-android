package io.github.kickoman.qiyaa.data

enum class AccentTheme(val key: String) {
    CLASSIC_GREEN("green"),
    AMBER("amber"),
    ICE_BLUE("ice"),
    ;

    fun next(): AccentTheme = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = AMBER

        fun fromKey(key: String?): AccentTheme = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
