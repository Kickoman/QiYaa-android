package io.github.kickoman.qiyaa.data

/** The interface languages: Belarusian unless the user picked another. */
enum class AppLanguage(val tag: String, val code: String) {
    BELARUSIAN("be", "BE"),
    RUSSIAN("ru", "RU"),
    ENGLISH("en", "EN"),
    ;

    companion object {
        val DEFAULT = BELARUSIAN

        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}
