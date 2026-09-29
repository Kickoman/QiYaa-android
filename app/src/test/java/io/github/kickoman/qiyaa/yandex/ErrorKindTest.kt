package io.github.kickoman.qiyaa.yandex

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorKindTest {
    @Test
    fun `failed connections are NoNetwork, also deep in the cause chain`() {
        assertEquals(ErrorKind.NoNetwork, ErrorKind.of(NetworkException("GET", "/x", IOException("offline"))))
        assertEquals(
            ErrorKind.NoNetwork,
            ErrorKind.of(IOException("load failed", UnknownHostException("api"))),
        )
        assertEquals(ErrorKind.NoNetwork, ErrorKind.of(SocketTimeoutException("read timed out")))
        assertEquals(ErrorKind.NoNetwork, ErrorKind.of(ConnectException("refused")))
    }

    @Test
    fun `401, 403, no account and an account without uid are a rejected token`() {
        assertEquals(ErrorKind.TokenRejected, ErrorKind.of(HttpException(401, "GET", "/x", "")))
        assertEquals(ErrorKind.TokenRejected, ErrorKind.of(HttpException(403, "GET", "/x", "")))
        assertEquals(ErrorKind.TokenRejected, ErrorKind.of(NotSignedInException("/users//likes/tracks")))
        assertEquals(
            ErrorKind.TokenRejected,
            ErrorKind.of(AuthException("GET /account/status returned no uid")),
        )
    }

    @Test
    fun `other HTTP statuses are a server error that keeps the status`() {
        assertEquals(ErrorKind.ServerError(404), ErrorKind.of(HttpException(404, "GET", "/x", "Not found")))
        assertEquals(
            ErrorKind.ServerError(503),
            ErrorKind.of(IOException("wrapped", HttpException(503, "GET", "/x", ""))),
        )
    }

    @Test
    fun `a reply the app cannot read is Malformed`() {
        assertEquals(ErrorKind.Malformed, ErrorKind.of(MalformedResponseException("GET", "/x", "no result")))
    }

    @Test
    fun `a refused sign-in and an expired device code have kinds of their own`() {
        assertEquals(
            ErrorKind.SignInRefused,
            ErrorKind.of(OAuthException(400, "POST", "/device/code", "Client not found")),
        )
        assertEquals(ErrorKind.CodeExpired, ErrorKind.of(CodeExpiredException()))
    }

    @Test
    fun `anything else is Unknown`() {
        assertEquals(ErrorKind.Unknown, ErrorKind.of(IllegalStateException("boom")))
        assertEquals(ErrorKind.Unknown, ErrorKind.of(YandexException("boom")))
    }
}
