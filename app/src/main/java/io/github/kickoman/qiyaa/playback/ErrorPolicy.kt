package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.yandex.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

sealed interface ErrorAction {
    data object WaitForNetwork : ErrorAction

    data object Hold : ErrorAction

    data object SkipToNext : ErrorAction

    data object Stop : ErrorAction
}

object ErrorPolicy {
    const val MAX_CONSECUTIVE_TRACK_FAILURES = 3

    fun decide(kind: FailureKind, consecutiveTrackFailures: Int, hasNext: Boolean): ErrorAction =
        when (kind) {
            FailureKind.NETWORK -> ErrorAction.WaitForNetwork
            FailureKind.SESSION -> ErrorAction.Hold
            FailureKind.TRACK ->
                if (!hasNext || consecutiveTrackFailures + 1 >= MAX_CONSECUTIVE_TRACK_FAILURES) {
                    ErrorAction.Stop
                } else {
                    ErrorAction.SkipToNext
                }
        }

    suspend fun awaitRetry(connectivity: Flow<Boolean>, attempt: Int) {
        if (!connectivity.first()) {
            connectivity.first { it }
            return
        }
        delay(retryDelayMs(attempt))
    }

    fun retryDelayMs(attempt: Int): Long {
        var delayMs = Session.FIRST_RETRY_MS
        repeat(attempt) { delayMs = minOf(delayMs * 2, Session.MAX_RETRY_MS) }
        return delayMs
    }
}
