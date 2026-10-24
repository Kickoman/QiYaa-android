package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.MalformedResponseException
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `the kind of a playback error names what the user can do about it`() {
        assertEquals(ErrorKind.NoNetwork, kind(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(
            ErrorKind.TokenRejected,
            kind(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpException(401, "GET", "/tracks/1/download-info", ""),
            ),
        )
        assertEquals(
            ErrorKind.ServerError(404),
            kind(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpException(404, "GET", "/tracks/1", "Not found"),
            ),
        )
        assertEquals(
            ErrorKind.TrackUnplayable,
            kind(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                MalformedResponseException("GET", "/tracks/1/download-info", "no download variants"),
            ),
        )
        assertEquals(ErrorKind.TrackUnplayable, kind(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test
    fun `audio failures are sorted by code into init, device and undecodable, others are none`() {
        val expected =
            mapOf(
                5001 to AudioFailure.INIT,
                5004 to AudioFailure.INIT,
                5002 to AudioFailure.DEVICE,
                5003 to AudioFailure.DEVICE,
                4006 to AudioFailure.DEVICE,
                3001 to AudioFailure.UNDECODABLE,
                3002 to AudioFailure.UNDECODABLE,
                3003 to AudioFailure.UNDECODABLE,
                3004 to AudioFailure.UNDECODABLE,
                4001 to AudioFailure.UNDECODABLE,
                4002 to AudioFailure.UNDECODABLE,
                4003 to AudioFailure.UNDECODABLE,
                4004 to AudioFailure.UNDECODABLE,
                4005 to AudioFailure.UNDECODABLE,
            )
        for ((code, failure) in expected) assertEquals("$code", failure, PlaybackFailures.audioFailure(code))
        val others =
            listOf(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                4_007,
                5_005,
                -1,
            )
        for (code in others) assertEquals("$code", null, PlaybackFailures.audioFailure(code))
    }

    private fun kind(errorCode: Int, cause: Throwable? = null) = PlaybackFailures.errorKind(errorCode, cause)

    private fun classify(errorCode: Int, cause: Throwable? = null) =
        PlaybackFailures.classify(errorCode, cause)

    @Test
    fun `LOCAL-13 audio that no decoder takes is a failure, while audio that one takes, or none, is not`() {
        assertTrue(
            PlaybackFailures.hasNoPlayableAudio(tracks(audio("audio/alac", C.FORMAT_UNSUPPORTED_SUBTYPE))),
        )
        assertFalse(PlaybackFailures.hasNoPlayableAudio(tracks(audio("audio/flac", C.FORMAT_HANDLED))))
        assertFalse(
            PlaybackFailures.hasNoPlayableAudio(tracks(audio("audio/flac", C.FORMAT_EXCEEDS_CAPABILITIES))),
        )
        assertFalse(PlaybackFailures.hasNoPlayableAudio(Tracks.EMPTY))
    }

    private fun audio(mimeType: String, support: Int): Tracks.Group = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mimeType).build()),
        false,
        intArrayOf(support),
        booleanArrayOf(false),
    )

    private fun tracks(vararg groups: Tracks.Group) = Tracks(groups.toList())
}
