package io.github.kickoman.qiyaa.yandex

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface ErrorKind {
    data object NoNetwork : ErrorKind

    data object TokenRejected : ErrorKind

    data class ServerError(val status: Int) : ErrorKind

    data object Malformed : ErrorKind

    data object SignInRefused : ErrorKind

    data object CodeExpired : ErrorKind

    data object TrackUnplayable : ErrorKind

    data object Unknown : ErrorKind

    companion object {
        fun of(failed: Throwable): ErrorKind = causes(failed).firstNotNullOfOrNull(::direct) ?: Unknown

        fun isNetworkFailure(failure: Throwable): Boolean = failure is NetworkException ||
            failure is UnknownHostException ||
            failure is ConnectException ||
            failure is SocketTimeoutException ||
            failure is NoRouteToHostException

        fun causes(first: Throwable?): List<Throwable> {
            val chain = ArrayList<Throwable>()
            var current = first
            while (current != null && current !in chain) {
                chain += current
                current = current.cause
            }
            return chain
        }

        private fun direct(failure: Throwable): ErrorKind? = when {
            isNetworkFailure(failure) -> NoNetwork
            failure is HttpException -> if (failure.isTokenRejected) {
                TokenRejected
            } else {
                ServerError(
                    failure.status,
                )
            }
            failure is NotSignedInException -> TokenRejected
            failure is OAuthException -> SignInRefused
            failure is CodeExpiredException -> CodeExpired
            failure is AuthException -> TokenRejected
            failure is MalformedResponseException -> Malformed
            else -> null
        }
    }
}
