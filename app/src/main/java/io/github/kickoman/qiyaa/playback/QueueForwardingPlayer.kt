package io.github.kickoman.qiyaa.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import io.github.kickoman.qiyaa.queue.QueueController

// The player the MediaSession sees: Next and Previous from the notification, the headset and
// the app's own MediaController all go to QueueController. The transport commands stay
// available at the end of a queue, because Next there still means something (WAVE-09, TR-04).
@UnstableApi
class QueueForwardingPlayer(player: Player, private val queue: QueueController) : ForwardingPlayer(player) {
    private val wrappers = HashMap<Player.Listener, Player.Listener>()

    override fun seekToNext() = queue.next()

    override fun seekToNextMediaItem() = queue.next()

    override fun seekToPrevious() = queue.previous()

    override fun seekToPreviousMediaItem() = queue.previous()

    override fun getAvailableCommands(): Player.Commands = withTransport(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun addListener(listener: Player.Listener) {
        val wrapper = TransportCommandsListener(listener)
        wrappers[listener] = wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        wrappers.remove(listener)?.let { super.removeListener(it) }
    }

    private fun withTransport(commands: Player.Commands): Player.Commands {
        if (mediaItemCount == 0) return commands
        return commands.buildUpon().addAll(*TRANSPORT).build()
    }

    // Kotlin's `by` does not forward Java default methods, and every Player.Listener method is one:
    // each callback has to be passed on by hand.
    private inner class TransportCommandsListener(private val listener: Player.Listener) : Player.Listener {
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            listener.onAvailableCommandsChanged(withTransport(availableCommands))
        }

        override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(player, events)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) =
            listener.onTimelineChanged(timeline, reason)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            listener.onMediaItemTransition(mediaItem, reason)

        override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
            listener.onMediaMetadataChanged(mediaMetadata)

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
            listener.onPlaylistMetadataChanged(mediaMetadata)

        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
            listener.onTrackSelectionParametersChanged(parameters)

        override fun onPlaybackStateChanged(playbackState: Int) =
            listener.onPlaybackStateChanged(playbackState)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            listener.onPlayWhenReadyChanged(playWhenReady, reason)

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

        override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)

        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)

        override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)

        override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = listener.onPositionDiscontinuity(oldPosition, newPosition, reason)

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            listener.onPlaybackParametersChanged(playbackParameters)

        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
            listener.onSeekBackIncrementChanged(seekBackIncrementMs)

        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
            listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)

        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

        override fun onAudioSessionIdChanged(audioSessionId: Int) =
            listener.onAudioSessionIdChanged(audioSessionId)

        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
            listener.onAudioAttributesChanged(audioAttributes)

        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
            listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)

        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
            listener.onDeviceVolumeChanged(volume, muted)

        override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)

        override fun onSurfaceSizeChanged(width: Int, height: Int) =
            listener.onSurfaceSizeChanged(width, height)

        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()

        override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)

        override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)

        // Deprecated callbacks are passed on too, as Media3's own ForwardingListener does.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
            listener.onPlayerStateChanged(playWhenReady, playbackState)

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCues(cues: List<Cue>) = listener.onCues(cues)
    }

    private companion object {
        val TRANSPORT =
            intArrayOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
    }
}
