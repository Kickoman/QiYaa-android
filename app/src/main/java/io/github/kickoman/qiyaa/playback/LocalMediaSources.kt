package io.github.kickoman.qiyaa.playback

import android.content.ContentResolver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier

@UnstableApi
class LocalMediaSources(private val delegate: MediaSource.Factory) : MediaSource.Factory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = delegate.createMediaSource(mediaItem)
        val local = mediaItem.localConfiguration?.uri?.scheme == ContentResolver.SCHEME_CONTENT
        return if (local) PlayedDiscardingSource(source) else source
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
        apply { delegate.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
        apply { delegate.setLoadErrorHandlingPolicy(policy) }

    override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory): MediaSource.Factory =
        apply { delegate.setCmcdConfigurationFactory(factory) }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): MediaSource.Factory =
        apply { delegate.setSubtitleParserFactory(factory) }

    override fun setDownloadExecutor(executor: Supplier<ReleasableExecutor>): MediaSource.Factory =
        apply { delegate.setDownloadExecutor(executor) }

    companion object {
        fun discardBefore(positionUs: Long): Long = positionUs + PlayerBuffer.BACK_BUFFER_MS * 1_000L
    }
}

@UnstableApi
private class PlayedDiscardingSource(source: MediaSource) : WrappingMediaSource(source) {
    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = PlayedDiscardingPeriod(mediaSource.createPeriod(id, allocator, startPositionUs))

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as PlayedDiscardingPeriod).wrapped)
    }
}

@UnstableApi
private class PlayedDiscardingPeriod(val wrapped: MediaPeriod) : MediaPeriod {
    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        val outer = this
        wrapped.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(outer)

                override fun onContinueLoadingRequested(source: MediaPeriod) =
                    callback.onContinueLoadingRequested(outer)
            },
            positionUs,
        )
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        wrapped.discardBuffer(LocalMediaSources.discardBefore(positionUs), toKeyframe)

    override fun maybeThrowPrepareError() = wrapped.maybeThrowPrepareError()

    override fun getTrackGroups(): TrackGroupArray = wrapped.trackGroups

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long = wrapped.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)

    override fun readDiscontinuity(): Long = wrapped.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long = wrapped.seekToUs(positionUs)

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        wrapped.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = wrapped.bufferedPositionUs

    override fun getNextLoadPositionUs(): Long = wrapped.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = wrapped.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = wrapped.isLoading

    override fun reevaluateBuffer(positionUs: Long) = wrapped.reevaluateBuffer(positionUs)
}
