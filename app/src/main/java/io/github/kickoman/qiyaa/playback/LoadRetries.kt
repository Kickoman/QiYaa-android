package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.kickoman.qiyaa.spotify.CdnStatusException
import io.github.kickoman.qiyaa.spotify.KeyFailedException
import io.github.kickoman.qiyaa.spotify.KeyRefusedException
import io.github.kickoman.qiyaa.spotify.LoginRefusedException
import io.github.kickoman.qiyaa.spotify.NoPlayableFileException
import io.github.kickoman.qiyaa.spotify.NotLoggedInException
import io.github.kickoman.qiyaa.spotify.SpotifyContentException
import io.github.kickoman.qiyaa.yandex.ErrorKind

@UnstableApi
class LoadRetries : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isFinal(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)

    companion object {
        fun isFinal(failed: Throwable): Boolean = ErrorKind.causes(failed).any(::isFinalCause)

        private fun isFinalCause(failure: Throwable): Boolean = failure is KeyRefusedException ||
            failure is KeyFailedException ||
            failure is CdnStatusException ||
            failure is LoginRefusedException ||
            failure is NoPlayableFileException ||
            failure is NotLoggedInException ||
            failure is SpotifyContentException
    }
}
