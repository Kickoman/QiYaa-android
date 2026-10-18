package io.github.kickoman.qiyaa.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.HttpException

enum class AudioFailure { INIT, DEVICE, UNDECODABLE }

object PlaybackFailures {
    private val NETWORK_CODES =
        setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

    private val AUDIO_FAILURES: Map<Int, AudioFailure> =
        mapOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED to AudioFailure.INIT,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED to AudioFailure.INIT,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED to AudioFailure.DEVICE,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED to AudioFailure.DEVICE,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED to AudioFailure.DEVICE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_DECODING_FAILED to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES to AudioFailure.UNDECODABLE,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED to AudioFailure.UNDECODABLE,
        )

    fun classify(errorCode: Int, cause: Throwable?): FailureKind {
        val chain = ErrorKind.causes(cause)
        return when {
            chain.any { it is HttpException && it.isTokenRejected } -> FailureKind.SESSION
            errorCode in NETWORK_CODES || chain.any(ErrorKind::isNetworkFailure) -> FailureKind.NETWORK
            else -> FailureKind.TRACK
        }
    }

    fun audioFailure(errorCode: Int): AudioFailure? = AUDIO_FAILURES[errorCode]

    fun errorKind(errorCode: Int, cause: Throwable?): ErrorKind = when (classify(errorCode, cause)) {
        FailureKind.SESSION -> ErrorKind.TokenRejected
        FailureKind.NETWORK -> ErrorKind.NoNetwork
        FailureKind.TRACK -> httpStatus(cause)?.let(ErrorKind::ServerError) ?: ErrorKind.TrackUnplayable
    }

    private fun httpStatus(cause: Throwable?): Int? = ErrorKind.causes(cause).firstNotNullOfOrNull {
        when (it) {
            is HttpException -> it.status
            is HttpDataSource.InvalidResponseCodeException -> it.responseCode
            else -> null
        }
    }
}
