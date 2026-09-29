package io.github.kickoman.qiyaa.playback

import androidx.media3.common.PlaybackException
import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object PlaybackFailures {
    private val NETWORK_CODES =
        setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

    fun classify(errorCode: Int, cause: Throwable?): FailureKind {
        val chain = causes(cause)
        return when {
            chain.any { it is HttpException && it.isTokenRejected } -> FailureKind.SESSION
            errorCode in NETWORK_CODES || chain.any(::isNetworkFailure) -> FailureKind.NETWORK
            else -> FailureKind.TRACK
        }
    }

    private fun isNetworkFailure(failure: Throwable): Boolean = failure is NetworkException ||
        failure is UnknownHostException ||
        failure is ConnectException ||
        failure is SocketTimeoutException ||
        failure is NoRouteToHostException

    private fun causes(first: Throwable?): List<Throwable> {
        val chain = ArrayList<Throwable>()
        var current = first
        while (current != null && current !in chain) {
            chain += current
            current = current.cause
        }
        return chain
    }
}
