package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.queue.PlayOrder
import io.github.kickoman.qiyaa.queue.PlayerEngine
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.yandex.Track

// Mirrors the parts of ExoPlayer the queue relies on: a playlist with a cursor, the end state,
// a shuffle order, repeat of the whole queue, and listener callbacks fired synchronously after
// each change.
class FakeEngine(private val controller: QueueController) : PlayerEngine {
    val tracks = ArrayList<Track>()
    val commands = ArrayList<String>()
    var playWhenReady = false
        private set
    var shuffleOrder: List<Int>? = null

    override var currentIndex = 0
        private set
    override var isEnded = false
        private set

    override val itemCount: Int get() = tracks.size
    override var positionMs = 0L

    override var shuffleEnabled = false
        set(value) {
            if (field == value) return
            field = value
            commands += "shuffle $value"
            controller.onShuffleChanged(value)
        }

    override var repeatEnabled = false
        set(value) {
            if (field == value) return
            field = value
            commands += "repeat $value"
            controller.onRepeatChanged(value)
        }

    override fun hasNext(): Boolean =
        PlayOrder.remainingAfter(playOrder(), currentIndex) > 0 || (repeatEnabled && tracks.isNotEmpty())

    override fun playOrder(): List<Int> =
        if (shuffleEnabled) shuffleOrder ?: tracks.indices.toList() else tracks.indices.toList()

    override fun hasPrevious(): Boolean {
        val order = playOrder()
        return order.indexOf(currentIndex) > 0 || (repeatEnabled && tracks.isNotEmpty())
    }

    override fun setTracks(
        tracks: List<Track>,
        playWhenReady: Boolean,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        val start = if (startIndex == 0 && startPositionMs == 0L) "" else " at $startIndex:$startPositionMs"
        commands += "set ${tracks.map { it.id }} play=$playWhenReady$start"
        this.tracks.clear()
        this.tracks += tracks
        currentIndex = startIndex
        positionMs = startPositionMs
        isEnded = false
        this.playWhenReady = playWhenReady
        controller.onItemChanged(tracks.getOrNull(startIndex))
    }

    override fun appendTracks(tracks: List<Track>) {
        commands += "append ${tracks.map { it.id }}"
        this.tracks += tracks
    }

    override fun removeAt(index: Int) {
        commands += "remove $index"
        tracks.removeAt(index)
        if (index < currentIndex) currentIndex--
    }

    override fun clear() {
        commands += "clear"
        tracks.clear()
        currentIndex = 0
    }

    override fun seekTo(index: Int) {
        commands += "seek $index"
        currentIndex = index
        positionMs = 0
        isEnded = false
        controller.onItemChanged(tracks[index])
    }

    override fun skipToNext() {
        commands += "next"
        nextInOrder()?.let { next ->
            currentIndex = next
            controller.onItemChanged(tracks[currentIndex])
        }
    }

    override fun skipToPrevious() {
        commands += "previous"
        val order = playOrder()
        val position = order.indexOf(currentIndex)
        val previous = if (position >
            0
        ) {
            order[position - 1]
        } else if (repeatEnabled && order.isNotEmpty()) {
            order.last()
        } else {
            null
        }
        if (previous != null) {
            currentIndex = previous
            positionMs = 0
            controller.onItemChanged(tracks[currentIndex])
        }
    }

    override fun seekToPosition(positionMs: Long) {
        commands += "seek to $positionMs"
        this.positionMs = positionMs
        isEnded = false
    }

    override fun pause() {
        commands += "pause"
        playWhenReady = false
    }

    override fun prepare() {
        commands += "prepare"
    }

    override fun play() {
        commands += "play"
        playWhenReady = true
    }

    override fun stop() {
        commands += "stop"
    }

    fun finishTrack() {
        val next = nextInOrder()
        if (next != null) {
            currentIndex = next
            controller.onItemChanged(tracks[currentIndex])
        } else {
            isEnded = true
            controller.onEnded()
        }
    }

    private fun nextInOrder(): Int? {
        val order = playOrder()
        val position = order.indexOf(currentIndex)
        return when {
            position in 0 until order.size - 1 -> order[position + 1]
            repeatEnabled && order.isNotEmpty() -> order.first()
            else -> null
        }
    }

    fun startPlaying() = controller.onPlayingChanged(true)

    fun fail(kind: FailureKind, message: String = "boom") = controller.onFailure(kind, message)

    fun ids(): List<String> = tracks.map { it.id }
}
