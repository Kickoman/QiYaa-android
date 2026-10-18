package io.github.kickoman.qiyaa.data

enum class ShuffleChoice(val key: String) {
    WITHOUT_REPEATS("without-repeats"),
    RANDOM_TRACK("random"),
    ;

    companion object {
        val DEFAULT = WITHOUT_REPEATS

        fun fromKey(key: String?): ShuffleChoice = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
