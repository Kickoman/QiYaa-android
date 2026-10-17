package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.queue.HeadShuffle
import io.github.kickoman.qiyaa.queue.PlayOrder
import io.github.kickoman.qiyaa.queue.PlayerEngine
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.Transition
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.Track

// Mirrors the parts of ExoPlayer the queue relies on: a playlist with a cursor, the end state,
// a shuffle order, repeat of the whole queue, and listener callbacks fired synchronously after
// each change.
class FakeEngine(private val controller: QueueController) : PlayerEngine {
    val tracks = ArrayList<Track>()
    val commands = ArrayList<String>()
    var playWhenReady = false
        private set
    var playing = false
        private set
    var shuffle = HeadShuffle.EMPTY
        private set
    var shuffleOrder: List<Int>
        get() = shuffle.head
        set(value) {
            shuffle = HeadShuffle(value, tracks.size)
        }

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
        }

    override var repeatEnabled = false
        set(value) {
            if (field == value) return
            field = value
            commands += "repeat $value"
        }

    override var pauseAtEndOfCurrent = false
        set(value) {
            if (field == value) return
            field = value
            commands += "pause at end $value"
        }

    override fun hasNext(): Boolean =
        PlayOrder.remainingAfter(playOrder(), currentIndex) > 0 || (repeatEnabled && tracks.isNotEmpty())

    override fun playOrder(): List<Int> = if (shuffleEnabled) shuffle.order else tracks.indices.toList()

    override fun hasPrevious(): Boolean {
        val order = playOrder()
        return order.indexOf(currentIndex) > 0 || (repeatEnabled && tracks.isNotEmpty())
    }

    override fun setTracks(
        tracks: List<Track>,
        playWhenReady: Boolean,
        startIndex: Int,
        startPositionMs: Long,
        headSize: Int,
    ) {
        val start = if (startIndex == 0 && startPositionMs == 0L) "" else " at $startIndex:$startPositionMs"
        commands += "set ${tracks.map { it.id }} play=$playWhenReady$start"
        this.tracks.clear()
        this.tracks += tracks
        shuffle = pass(headSize, startIndex)
        currentIndex = startIndex
        positionMs = startPositionMs
        isEnded = false
        this.playWhenReady = playWhenReady
        report(tracks.getOrNull(startIndex), Transition.NEW_QUEUE)
    }

    override fun shuffleHead(headSize: Int, first: Int) {
        shuffle = pass(headSize, first)
    }

    private fun pass(headSize: Int, first: Int): HeadShuffle {
        val head = (0 until headSize.coerceIn(0, tracks.size)).toList()
        return HeadShuffle(if (first in head) listOf(first) + (head - first) else head, tracks.size)
    }

    override fun appendTracks(tracks: List<Track>) {
        commands += "append ${tracks.map { it.id }}"
        insert(this.tracks.size, tracks)
    }

    override fun insertTracks(index: Int, tracks: List<Track>) {
        commands += "insert $index ${tracks.map { it.id }}"
        insert(index, tracks)
    }

    private fun insert(index: Int, added: List<Track>) {
        val wasEmpty = tracks.isEmpty()
        tracks.addAll(index, added)
        shuffle = shuffle.inserted(index, added.size)
        if (!wasEmpty && index <= currentIndex) currentIndex += added.size
        if (wasEmpty && tracks.isNotEmpty()) {
            currentIndex = 0
            report(tracks[0], Transition.NEW_QUEUE)
        }
    }

    override fun removeAt(index: Int) {
        commands += "remove $index"
        tracks.removeAt(index)
        shuffle = shuffle.removed(index, index + 1)
        if (index < currentIndex) {
            currentIndex--
        } else if (index == currentIndex) {
            report(tracks.getOrNull(currentIndex), Transition.NEW_QUEUE)
        }
    }

    override fun clear() {
        commands += "clear"
        tracks.clear()
        shuffle = HeadShuffle.EMPTY
        currentIndex = 0
    }

    override fun seekTo(index: Int) {
        commands += "seek $index"
        positionMs = 0
        isEnded = false
        if (index == currentIndex) {
            controller.onSeeked()
            return
        }
        currentIndex = index
        report(tracks[index], Transition.SEEK)
    }

    override fun skipToNext() {
        commands += "next"
        nextInOrder()?.let { next ->
            currentIndex = next
            positionMs = 0
            isEnded = false
            report(tracks[currentIndex], Transition.SEEK)
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
            report(tracks[currentIndex], Transition.SEEK)
        }
    }

    override fun seekToPosition(positionMs: Long) {
        commands += "seek to $positionMs"
        this.positionMs = positionMs
        isEnded = false
        controller.onSeeked()
    }

    override fun pause() {
        commands += "pause"
        playWhenReady = false
        if (playing) stopPlaying()
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
        if (pauseAtEndOfCurrent) {
            playWhenReady = false
            controller.onPausedAtEnd()
            if (!playWhenReady && playing) stopPlaying()
            return
        }
        val next = nextInOrder()
        if (next != null) {
            val transition = if (next == currentIndex) Transition.REPEAT else Transition.AUTO
            currentIndex = next
            positionMs = 0
            report(tracks[currentIndex], transition)
        } else {
            isEnded = true
            controller.onEnded()
            if (playing) stopPlaying()
        }
    }

    private fun report(track: Track?, transition: Transition) {
        check(shuffle.length == tracks.size) {
            "The shuffle order has ${shuffle.length} items, the playlist ${tracks.size}: $track"
        }
        controller.onItemChanged(track, transition, playing)
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

    fun startPlaying() {
        playing = true
        controller.onPlayingChanged(true)
    }

    fun stopPlaying() {
        playing = false
        controller.onPlayingChanged(false)
    }

    fun fail(kind: FailureKind, error: ErrorKind = ErrorKind.TrackUnplayable) =
        controller.onFailure(kind, error)

    fun ids(): List<String> = tracks.map { it.id }
}
