package io.github.kickoman.qiyaa.ui

enum class LocalAccess { GRANTED, ASK, ASK_AGAIN, SETTINGS }

object LocalAccessRule {
    fun before(granted: Boolean, askedBefore: Boolean): LocalAccess = when {
        granted -> LocalAccess.GRANTED
        askedBefore -> LocalAccess.ASK_AGAIN
        else -> LocalAccess.ASK
    }

    fun after(granted: Boolean, rationale: Boolean, askedBefore: Boolean): LocalAccess = when {
        granted -> LocalAccess.GRANTED
        rationale || !askedBefore -> LocalAccess.ASK_AGAIN
        else -> LocalAccess.SETTINGS
    }
}
