package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.Login5Reply
import java.io.IOException

open class SpotifyException(message: String) : IOException(message)

class SpotifyHttpException(val status: Int, val method: String, val path: String) :
    SpotifyException("HTTP $status on $method $path")

class SpotifyNetworkException(what: String, cause: IOException) :
    SpotifyException("$what failed: ${cause.message ?: cause.javaClass.simpleName}") {
    init {
        initCause(cause)
    }
}

class SpotifyContentException(detail: String) : SpotifyException(detail)

class LoginRefusedException(val code: Int, val where: String) :
    SpotifyException("Spotify refused the login at $where: code $code") {
    val isTransient: Boolean
        get() = where == SpotifyWeb.LOGIN5_PATH && code in Login5Reply.Error.TRANSIENT_CODES
}

class CdnStatusException(val status: Int, val path: String) :
    SpotifyException("HTTP $status on GET $path, the last of the CDN links")

class AuthorizationException(val error: String, val path: String) :
    SpotifyException("Spotify did not authorize the login on $path: $error")

class KeyRefusedException : SpotifyException("Spotify refused the audio key to this account: code 1")

class KeyFailedException(detail: String) : SpotifyException("No audio key after two attempts: $detail")

class NotLoggedInException : SpotifyException("No saved Spotify login")

class QueryOutdatedException(val operation: String) :
    SpotifyException("Spotify refused every known hash of the Pathfinder query $operation")

class NoPlayableFileException(val trackId: String, detail: String = "no Ogg Vorbis file to play") :
    SpotifyException("Spotify track $trackId has $detail")
