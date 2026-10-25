package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.kickoman.qiyaa.spotify.CdnStatusException
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
import io.github.kickoman.qiyaa.spotify.protocol.Login5Reply
import io.github.kickoman.qiyaa.yandex.HttpException
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadRetriesTest {
    private val policy = LoadRetries()

    @Test
    fun `SPOT-05 SPOT-06 SPOT-15 SPOT-16 SPOT-17 Spotify's refusals of the track or account are final`() {
        for (failed in finalFailures()) {
            assertTrue("$failed", LoadRetries.isFinal(failed))
            assertTrue("wrapped $failed", LoadRetries.isFinal(IOException("load failed", failed)))
            assertTrue(
                "deeper $failed",
                LoadRetries.isFinal(IOException("load failed", IOException("open failed", failed))),
            )
        }
    }

    @Test
    fun `network failures, statuses and everything of Yandex and local files are not final`() {
        for (failed in retriedFailures()) assertFalse("$failed", LoadRetries.isFinal(failed))
    }

    @Test
    fun `a final failure is not retried, and is fatal at once`() {
        for (failed in finalFailures()) {
            val delays = (1..4).map { policy.getRetryDelayMsFor(info(failed, it)) }
            assertEquals("$failed", List(4) { C.TIME_UNSET }, delays)
        }
    }

    @Test
    fun `everything else is retried as Media3 does, with pauses of 0, 1 and 2 s`() {
        for (failed in retriedFailures()) {
            val delays = (1..3).map { policy.getRetryDelayMsFor(info(failed, it)) }
            assertEquals("$failed", listOf(0L, 1_000L, 2_000L), delays)
        }
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(FileNotFoundException("gone"), 1)))
        assertEquals(3, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    private fun finalFailures(): List<IOException> = listOf(
        KeyRefusedException(),
        KeyFailedException("AesKeyError code 2"),
        LoginRefusedException(11, SpotifySession.ACCESS_POINT),
        LoginRefusedException(3, SpotifyWeb.LOGIN5_PATH),
        LoginRefusedException(1, SpotifyWeb.LOGIN5_PATH),
        CdnStatusException(403, "audio-fa.scdn.co/audio/03"),
        CdnStatusException(503, "audio-ak.spotifycdn.com/audio/03"),
        NoPlayableFileException(TRACK),
        NotLoggedInException(),
        SpotifyContentException("POST /extended-metadata/v0/extended-metadata: entities that do not parse"),
    )

    private fun retriedFailures(): List<IOException> = listOf(
        SpotifyNetworkException("an audio key", SocketTimeoutException("no access point login in 20000 ms")),
        SpotifyHttpException(503, "POST", "/extended-metadata/v0/extended-metadata"),
        LoginRefusedException(Login5Reply.Error.TIMEOUT, SpotifyWeb.LOGIN5_PATH),
        LoginRefusedException(Login5Reply.Error.TOO_MANY_ATTEMPTS, SpotifyWeb.LOGIN5_PATH),
        LoginRefusedException(Login5Reply.Error.TRY_AGAIN_LATER, SpotifyWeb.LOGIN5_PATH),
        NetworkException("GET", "/tracks/1/download-info", IOException("offline")),
        HttpException(404, "GET", "/tracks/1/download-info", "Not found"),
        HttpDataSource.InvalidResponseCodeException(
            403,
            "Forbidden",
            null,
            NO_HEADERS,
            anyDataSpec(),
            NO_BODY,
        ),
        IOException("disk"),
    )

    private fun info(failed: IOException, errorCount: Int) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo.Builder(1, anyDataSpec(), 0).build(),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        failed,
        errorCount,
    )

    // DataSpec rejects a null Uri, and android.net.Uri is a stub on the JVM; nothing here reads the spec.
    private fun anyDataSpec(): DataSpec {
        val unsafe = Class.forName("sun.misc.Unsafe")
        val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafe.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(instance, DataSpec::class.java) as DataSpec
    }

    private companion object {
        const val TRACK = "6PCoZdjSPbRMehHviTrpB3"
        val NO_BODY = ByteArray(0)
        val NO_HEADERS = emptyMap<String, List<String>>()
    }
}
