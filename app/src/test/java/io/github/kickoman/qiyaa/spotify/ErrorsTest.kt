package io.github.kickoman.qiyaa.spotify

import java.io.EOFException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ErrorsTest {
    @Test
    fun `a network failure keeps the original IOException as its cause`() {
        val cause = UnknownHostException("apresolve.spotify.com")
        val failed = SpotifyNetworkException("GET apresolve.spotify.com/", cause)
        assertSame(cause, failed.cause)
        assertEquals("GET apresolve.spotify.com/ failed: apresolve.spotify.com", failed.message)
        assertEquals(
            "an audio key failed: EOFException",
            SpotifyNetworkException("an audio key", EOFException()).message,
        )
    }

    @Test
    fun `messages name the request, the status and the code`() {
        assertEquals("HTTP 503 on GET /thing", SpotifyHttpException(503, "GET", "/thing").message)
        assertEquals(
            "Spotify refused the login at the access point: code 11",
            LoginRefusedException(11, SpotifySession.ACCESS_POINT).message,
        )
        assertEquals(
            "Spotify did not authorize the login on POST /api/token: access_denied",
            AuthorizationException("access_denied", "POST /api/token").message,
        )
        assertEquals("No saved Spotify login", NotLoggedInException().message)
    }
}
