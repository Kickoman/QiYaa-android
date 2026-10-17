package io.github.kickoman.qiyaa.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.kickoman.qiyaa.queue.HeadShuffle
import io.github.kickoman.qiyaa.queue.PlayerEngine
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.Transition
import io.github.kickoman.qiyaa.yandex.Track
import kotlin.random.Random

@OptIn(UnstableApi::class)
class Media3Engine(
    private val player: ExoPlayer,
    private val controller: QueueController,
    private val random: Random = Random.Default,
) : PlayerEngine {
    private var order = HeadShuffle.EMPTY

    private val listener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                controller.onItemChanged(
                    mediaItem?.let(MediaItems::toTrack),
                    transitionOf(reason),
                    player.isPlaying,
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) controller.onEnded()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                val sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                if (reason == Player.DISCONTINUITY_REASON_SEEK && sameItem) controller.onSeeked()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controller.onPlayingChanged(isPlaying)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val atEnd = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                if (!playWhenReady && atEnd) controller.onPausedAtEnd()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(LOG_TAG, "Playback failed: ${error.errorCodeName}", error)
                controller.onFailure(
                    PlaybackFailures.classify(error.errorCode, error.cause),
                    PlaybackFailures.errorKind(error.errorCode, error.cause),
                )
            }
        }

    private fun transitionOf(reason: Int): Transition = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> Transition.AUTO
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> Transition.REPEAT
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> Transition.SEEK
        else -> Transition.NEW_QUEUE
    }

    override val itemCount: Int get() = player.mediaItemCount
    override val currentIndex: Int get() = player.currentMediaItemIndex
    override val positionMs: Long get() = player.currentPosition
    override val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED

    override var shuffleEnabled: Boolean
        get() = player.shuffleModeEnabled
        set(value) {
            player.shuffleModeEnabled = value
        }

    override var repeatEnabled: Boolean
        get() = player.repeatMode != Player.REPEAT_MODE_OFF
        set(value) {
            player.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }

    override var pauseAtEndOfCurrent: Boolean
        get() = player.pauseAtEndOfMediaItems
        set(value) {
            player.pauseAtEndOfMediaItems = value
        }

    fun attach() {
        player.addListener(listener)
        order = HeadShuffleOrder.fitted(order, player.mediaItemCount)
        player.setShuffleOrder(HeadShuffleOrder(order, random))
        controller.attach(this)
    }

    fun detach() {
        player.removeListener(listener)
        controller.detach(this)
    }

    override fun hasNext(): Boolean = player.hasNextMediaItem()

    override fun hasPrevious(): Boolean = player.hasPreviousMediaItem()

    override fun playOrder(): List<Int> {
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return order
    }

    override fun setTracks(
        tracks: List<Track>,
        playWhenReady: Boolean,
        startIndex: Int,
        startPositionMs: Long,
        head: List<Int>,
        lead: List<Int>,
    ) {
        val pass = HeadShuffle.pass(head, tracks.size, lead, random)
        player.setShuffleOrder(HeadShuffleOrder.staging(order, player.mediaItemCount, pass, random))
        order = pass
        player.setMediaItems(tracks.map(MediaItems::toMediaItem), startIndex, startPositionMs)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun shuffleHead(head: List<Int>, lead: List<Int>) {
        order = HeadShuffle.pass(head, player.mediaItemCount, lead, random)
        player.setShuffleOrder(HeadShuffleOrder(order, random))
    }

    override fun appendTracks(tracks: List<Track>) {
        val count = player.mediaItemCount
        val appended = HeadShuffleOrder.afterInsert(order, count, count, tracks.size)
        if (count == 0) player.setShuffleOrder(HeadShuffleOrder.staging(order, 0, appended, random))
        order = appended
        player.addMediaItems(count, tracks.map(MediaItems::toMediaItem))
    }

    override fun insertTracks(index: Int, tracks: List<Track>) {
        val count = player.mediaItemCount
        val at = index.coerceAtMost(count)
        val spliced = HeadShuffleOrder.afterSplice(order, count, at, tracks.size)
        player.setShuffleOrder(HeadShuffleOrder.staging(order, count, spliced, random, at))
        order = spliced
        player.addMediaItems(at, tracks.map(MediaItems::toMediaItem))
    }

    override fun removeAt(index: Int) {
        order = HeadShuffleOrder.afterRemove(order, player.mediaItemCount, index)
        player.removeMediaItem(index)
    }

    override fun clear() {
        order = HeadShuffle.EMPTY
        player.clearMediaItems()
    }

    override fun seekTo(index: Int) = player.seekTo(index, 0L)

    override fun seekToPosition(positionMs: Long) = player.seekTo(positionMs)

    override fun skipToNext() = player.seekToNextMediaItem()

    override fun skipToPrevious() = player.seekToPreviousMediaItem()

    override fun pause() = player.pause()

    override fun prepare() = player.prepare()

    override fun play() = player.play()

    override fun stop() = player.stop()
}
