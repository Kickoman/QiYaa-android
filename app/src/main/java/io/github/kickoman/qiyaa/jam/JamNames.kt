package io.github.kickoman.qiyaa.jam

object JamNames {
    const val MAX_NAME = 24
    private val SPACES = Regex("[${JamCodec.ECMASCRIPT_SPACES}]+")

    fun clean(name: String): String? {
        val cleaned = name.replace(SPACES, " ").trim(' ')
        return cleaned.takeIf { it.codePointCount(0, it.length) in 1..MAX_NAME }
    }

    fun prefill(name: String): String = cut(name.replace(SPACES, " ").trim(' ')).trimEnd(' ')

    fun cut(name: String): String {
        if (name.codePointCount(0, name.length) <= MAX_NAME) return name
        return name.substring(0, name.offsetByCodePoints(0, MAX_NAME))
    }
}
