package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.spotify.KeyFailedException
import io.github.kickoman.qiyaa.spotify.KeyRefusedException
import io.github.kickoman.qiyaa.spotify.LoginRefusedException
import io.github.kickoman.qiyaa.spotify.NoPlayableFileException
import io.github.kickoman.qiyaa.spotify.NotLoggedInException
import io.github.kickoman.qiyaa.spotify.QueryOutdatedException
import io.github.kickoman.qiyaa.spotify.SpotifyContentException
import io.github.kickoman.qiyaa.spotify.SpotifyHttpException
import io.github.kickoman.qiyaa.spotify.SpotifyNetworkException
import io.github.kickoman.qiyaa.spotify.SpotifyWeb
import io.github.kickoman.qiyaa.yandex.ErrorKind

fun errorKindOf(failed: Throwable): ErrorKind =
    ErrorKind.causes(failed).firstNotNullOfOrNull(::spotifyKindOf) ?: ErrorKind.of(failed)

private fun spotifyKindOf(failure: Throwable): ErrorKind? = when (failure) {
    is SpotifyNetworkException -> ErrorKind.NoNetwork
    is SpotifyHttpException -> ErrorKind.ServerError(failure.status)
    is SpotifyContentException, is QueryOutdatedException -> ErrorKind.Malformed
    is NotLoggedInException -> ErrorKind.SpotifyLoggedOut
    is KeyRefusedException -> ErrorKind.SpotifyKeyRefused
    is KeyFailedException, is NoPlayableFileException -> ErrorKind.TrackUnplayable
    is LoginRefusedException -> loginRefusalOf(failure)
    else -> null
}

private fun loginRefusalOf(failure: LoginRefusedException): ErrorKind = when (failure.where) {
    SpotifyWeb.LOGIN5_PATH -> ErrorKind.SpotifyTokenRefused
    else -> ErrorKind.SpotifyLoginRefused
}
