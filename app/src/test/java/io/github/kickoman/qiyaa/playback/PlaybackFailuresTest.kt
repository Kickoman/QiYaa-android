package io.github.kickoman.qiyaa.playback

import androidx.media3.common.PlaybackException
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.MalformedResponseException
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailuresTest {
    @Test
    fun `connection failures and timeouts reported by the data source are network failures`() {
        assertEquals(FailureKind.NETWORK, classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(
            FailureKind.NETWORK,
            classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
    }

    @Test
    fun `a resolver NetworkException is a network failure even when wrapped`() {
        val network = NetworkException("GET", "/tracks/1/download-info", IOException("offline"))
        assertEquals(FailureKind.NETWORK, classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, network))
        assertEquals(
            FailureKind.NETWORK,
            classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("load failed", network)),
        )
    }

    @Test
    fun `an unknown host is a network failure`() {
        val cause = UnknownHostException("api.music.yandex.net")
        assertEquals(FailureKind.NETWORK, classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, cause))
    }

    @Test
    fun `a rejected token is a session failure, not a broken track`() {
        val rejected = HttpException(401, "GET", "/tracks/1/download-info", "Unauthorized")
        assertEquals(FailureKind.SESSION, classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, rejected))
    }

    @Test
    fun `bad HTTP statuses, missing variants and decoder errors are track failures`() {
        assertEquals(FailureKind.TRACK, classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(
            FailureKind.TRACK,
            classify(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpException(404, "GET", "/tracks/1", "Not found"),
            ),
        )
        assertEquals(
            FailureKind.TRACK,
            classify(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                MalformedResponseException("GET", "/tracks/1/download-info", "no download variants"),
            ),
        )
        assertEquals(FailureKind.TRACK, classify(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(FailureKind.TRACK, classify(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    private fun classify(errorCode: Int, cause: Throwable? = null) =
        PlaybackFailures.classify(errorCode, cause)
}
