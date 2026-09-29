package io.github.kickoman.qiyaa.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.HttpException

object PlaybackFailures {
    private val NETWORK_CODES =
        setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

    fun classify(errorCode: Int, cause: Throwable?): FailureKind {
        val chain = ErrorKind.causes(cause)
        return when {
            chain.any { it is HttpException && it.isTokenRejected } -> FailureKind.SESSION
            errorCode in NETWORK_CODES || chain.any(ErrorKind::isNetworkFailure) -> FailureKind.NETWORK
            else -> FailureKind.TRACK
        }
    }

    fun errorKind(errorCode: Int, cause: Throwable?): ErrorKind = when (classify(errorCode, cause)) {
        FailureKind.SESSION -> ErrorKind.TokenRejected
        FailureKind.NETWORK -> ErrorKind.NoNetwork
        FailureKind.TRACK -> httpStatus(cause)?.let(ErrorKind::ServerError) ?: ErrorKind.TrackUnplayable
    }

    private fun httpStatus(cause: Throwable?): Int? = ErrorKind.causes(cause).firstNotNullOfOrNull {
        when (it) {
            is HttpException -> it.status
            is HttpDataSource.InvalidResponseCodeException -> it.responseCode
            else -> null
        }
    }
}
