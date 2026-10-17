package io.github.kickoman.qiyaa.yandex

data class RequestFailure(val kind: Kind, val status: Int? = null) {
    enum class Kind { NETWORK, HTTP, CONTENT }

    companion object {
        fun of(failed: YandexException): RequestFailure? = when (failed) {
            is NetworkException -> RequestFailure(Kind.NETWORK)
            is HttpException -> RequestFailure(Kind.HTTP, failed.status)
            is MalformedResponseException -> RequestFailure(Kind.CONTENT)
            else -> null
        }

        fun unusable(status: Int): RequestFailure =
            if (status >= 400) RequestFailure(Kind.HTTP, status) else RequestFailure(Kind.CONTENT)
    }
}
