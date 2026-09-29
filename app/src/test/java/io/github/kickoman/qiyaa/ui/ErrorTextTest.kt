package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.yandex.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {
    @Test
    fun `every kind of error has a string of its own`() {
        val kinds =
            listOf(
                ErrorKind.NoNetwork,
                ErrorKind.TokenRejected,
                ErrorKind.ServerError(500),
                ErrorKind.Malformed,
                ErrorKind.SignInRefused,
                ErrorKind.CodeExpired,
                ErrorKind.TrackUnplayable,
                ErrorKind.Unknown,
            )
        assertEquals(kinds.size, kinds.map { it.text().id }.toSet().size)
    }

    @Test
    fun `a server error names its status`() {
        assertEquals(TextRes(R.string.error_server, listOf(503)), ErrorKind.ServerError(503).text())
    }
}
