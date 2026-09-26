package io.github.kickoman.qiyaa.yandex

import java.io.IOException

open class YandexException(message: String) : IOException(message)

class HttpException(val status: Int, val method: String, val path: String, val reason: String) :
    YandexException("HTTP $status on $method $path: $reason") {
    val isTokenRejected: Boolean get() = status == 401 || status == 403
}

class NetworkException(method: String, path: String, cause: IOException) :
    YandexException("$method $path failed: ${cause.message ?: cause.javaClass.simpleName}") {
    init {
        initCause(cause)
    }
}

class MalformedResponseException(method: String, path: String, detail: String) :
    YandexException("$method $path: $detail")

class AuthException(message: String) : YandexException(message)
