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
import io.github.kickoman.qiyaa.spotify.SpotifySession
import io.github.kickoman.qiyaa.spotify.SpotifyWeb
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyErrorKindsTest {
    @Test
    fun `Spotify's failures have their own kinds`() {
        val timeout = SocketTimeoutException("read timed out")
        assertEquals(ErrorKind.NoNetwork, errorKindOf(SpotifyNetworkException("GET spclient/x", timeout)))
        assertEquals(ErrorKind.ServerError(503), errorKindOf(SpotifyHttpException(503, "GET", "/x")))
        assertEquals(ErrorKind.ServerError(401), errorKindOf(SpotifyHttpException(401, "GET", "/x")))
        assertEquals(
            ErrorKind.Malformed,
            errorKindOf(SpotifyContentException("POST /x: a page that does not parse")),
        )
        assertEquals(ErrorKind.Malformed, errorKindOf(QueryOutdatedException("searchDesktop")))
        assertEquals(ErrorKind.SpotifyLoggedOut, errorKindOf(NotLoggedInException()))
        assertEquals(ErrorKind.SpotifyKeyRefused, errorKindOf(KeyRefusedException()))
        assertEquals(ErrorKind.TrackUnplayable, errorKindOf(KeyFailedException("no answer in 5 s")))
        assertEquals(
            ErrorKind.TrackUnplayable,
            errorKindOf(NoPlayableFileException("01TrY6CZi6JaolPzVQ3HW1")),
        )
        assertEquals(
            ErrorKind.SpotifyLoginRefused,
            errorKindOf(LoginRefusedException(11, SpotifySession.ACCESS_POINT)),
        )
        assertEquals(
            ErrorKind.SpotifyTokenRefused,
            errorKindOf(LoginRefusedException(3, SpotifyWeb.LOGIN5_PATH)),
        )
    }

    @Test
    fun `a Spotify failure is found down the cause chain`() {
        assertEquals(
            ErrorKind.SpotifyKeyRefused,
            errorKindOf(IOException("load failed", KeyRefusedException())),
        )
        assertEquals(
            ErrorKind.ServerError(500),
            errorKindOf(IOException("wrapped", SpotifyHttpException(500, "POST", "/x"))),
        )
    }

    @Test
    fun `anything else is classified as before`() {
        assertEquals(ErrorKind.TokenRejected, errorKindOf(HttpException(401, "GET", "/x", "")))
        assertEquals(ErrorKind.NoNetwork, errorKindOf(IOException("load failed", SocketTimeoutException())))
        assertEquals(ErrorKind.Unknown, errorKindOf(IllegalStateException("broken")))
    }
}
