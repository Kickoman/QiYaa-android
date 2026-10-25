package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.spotify.KeyFailedException
import io.github.kickoman.qiyaa.spotify.KeyRefusedException
import io.github.kickoman.qiyaa.spotify.LoginRefusedException
import io.github.kickoman.qiyaa.spotify.NoPlayableFileException
import io.github.kickoman.qiyaa.spotify.NotLoggedInException
import io.github.kickoman.qiyaa.spotify.SpotifyContentException
import io.github.kickoman.qiyaa.spotify.SpotifyHttpException
import io.github.kickoman.qiyaa.spotify.SpotifyNetworkException
import io.github.kickoman.qiyaa.spotify.SpotifySession
import io.github.kickoman.qiyaa.spotify.SpotifyWeb
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.MalformedResponseException
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
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
    fun `SPOT-06 SPOT-17 no Spotify login or a refused key is an account failure with its own kind`() {
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        for (failed in listOf(NotLoggedInException(), KeyRefusedException())) {
            assertEquals("$failed", FailureKind.SESSION, classify(io, failed))
            val wrapped = IOException("load failed", failed)
            assertEquals("wrapped $failed", FailureKind.SESSION, classify(io, wrapped))
        }
        assertEquals(ErrorKind.SpotifyLoggedOut, kind(io, NotLoggedInException()))
        assertEquals(ErrorKind.SpotifyKeyRefused, kind(io, IOException("load failed", KeyRefusedException())))
    }

    @Test
    fun `SPOT-05 a login the access point or login5 refused is an account failure with its own kind`() {
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val accessPoint = LoginRefusedException(11, SpotifySession.ACCESS_POINT)
        val login5 = LoginRefusedException(3, SpotifyWeb.LOGIN5_PATH)
        for (failed in listOf(accessPoint, login5)) {
            val wrapped = IOException("load failed", failed)
            assertEquals("$failed", FailureKind.SESSION, classify(io, failed))
            assertEquals("wrapped $failed", FailureKind.SESSION, classify(io, wrapped))
        }
        assertEquals(ErrorKind.SpotifyLoginRefused, kind(io, accessPoint))
        assertEquals(ErrorKind.SpotifyTokenRefused, kind(io, IOException("load failed", login5)))
    }

    @Test
    fun `SPOT-14 SPOT-18 a network failure on the way to the access point or the CDN is a network failure`() {
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val reset = SpotifyNetworkException("GET audio-fa.scdn.co/a", SocketException("Connection reset"))
        val keyWait = SpotifyNetworkException("an audio key", SocketTimeoutException("no access point login"))
        for (failed in listOf(reset, keyWait)) {
            assertEquals("$failed", FailureKind.NETWORK, classify(io, failed))
            assertEquals(ErrorKind.NoNetwork, kind(io, failed))
        }
    }

    @Test
    fun `SPOT-14 SPOT-15 SPOT-16 a failed key, no playable file and a CDN status are track failures`() {
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val failures =
            mapOf(
                KeyFailedException("no answer in 5000 ms") to ErrorKind.TrackUnplayable,
                NoPlayableFileException("6PCoZdjSPbRMehHviTrpB3") to ErrorKind.TrackUnplayable,
                SpotifyContentException("GET /storage-resolve: a reply that does not parse") to
                    ErrorKind.TrackUnplayable,
                SpotifyHttpException(403, "GET", "audio-fa.scdn.co/audio/03") to ErrorKind.ServerError(403),
                SpotifyHttpException(401, "GET", "audio-fa.scdn.co/audio/03") to ErrorKind.ServerError(401),
                SpotifyHttpException(502, "POST", "/extended-metadata/v0/extended-metadata") to
                    ErrorKind.ServerError(502),
            )
        for ((failed, shown) in failures) {
            assertEquals("$failed", FailureKind.TRACK, classify(io, failed))
            assertEquals("$failed", shown, kind(io, failed))
        }
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
