package io.github.kickoman.qiyaa.yandex

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsTest {
    @Test
    fun `every yandex exception is an IOException so ExoPlayer's resolver can throw it`() {
        val errors: List<Throwable> =
            listOf(
                HttpException(500, "GET", "/x", "boom"),
                NetworkException("GET", "/x", IOException("reset")),
                MalformedResponseException("GET", "/x", "no result"),
                AuthException("expired"),
                NotSignedInException("/users//likes/tracks"),
            )
        for (error in errors) assertTrue(error is IOException)
    }

    @Test
    fun `HttpException marks 401 and 403 as a rejected token`() {
        assertTrue(HttpException(401, "GET", "/x", "").isTokenRejected)
        assertTrue(HttpException(403, "GET", "/x", "").isTokenRejected)
        assertFalse(HttpException(404, "GET", "/x", "").isTokenRejected)
        assertFalse(HttpException(500, "GET", "/x", "").isTokenRejected)
    }

    @Test
    fun `NetworkException keeps the cause and names the request`() {
        val cause = IOException("connection reset")
        val error = NetworkException("POST", "/tracks/", cause)
        assertSame(cause, error.cause)
        assertEquals("POST /tracks/ failed: connection reset", error.message)
    }

    @Test
    fun `NotSignedInException names the path that needed an account`() {
        assertTrue(NotSignedInException("likes/tracks").message!!.contains("likes/tracks"))
    }
}
