package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.FailureKind
import io.github.kickoman.qiyaa.queue.HeadShuffle
import io.github.kickoman.qiyaa.queue.PlayOrder
import io.github.kickoman.qiyaa.queue.PlayerEngine
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.Transition
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.ErrorKind
import kotlin.random.Random

// Mirrors the parts of ExoPlayer the queue relies on: a playlist with a cursor, the end state,
// a shuffle order, repeat of the whole queue, and listener callbacks fired synchronously after
// each change.
class FakeEngine(private val controller: QueueController) : PlayerEngine {
    val tracks = ArrayList<Track>()
    val commands = ArrayList<String>()
    val leads = ArrayList<List<Int>>()
    var joinRandom: Random = Random(0)
    override var playWhenReady = false
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
        head: List<Int>,
        lead: List<Int>,
    ) {
        val start = if (startIndex == 0 && startPositionMs == 0L) "" else " at $startIndex:$startPositionMs"
        commands += "set ${tracks.map { it.id }} play=$playWhenReady$start"
        this.tracks.clear()
        this.tracks += tracks
        leads += lead
        shuffle = pass(head, lead)
        currentIndex = startIndex
        positionMs = startPositionMs
        isEnded = false
        this.playWhenReady = playWhenReady
        report(tracks.getOrNull(startIndex), Transition.NEW_QUEUE)
    }

    override fun shuffleHead(head: List<Int>, lead: List<Int>) {
        leads += lead
        shuffle = pass(head, lead)
    }

    private fun pass(head: List<Int>, lead: List<Int>): HeadShuffle {
        val front = lead.filter { it in tracks.indices }.distinct()
        val rest = head.filter { it in tracks.indices && it !in front }.distinct().sorted()
        return HeadShuffle(front + rest, tracks.size)
    }

    override fun appendTracks(tracks: List<Track>) {
        commands += "append ${tracks.map { it.id }}"
        insert(this.tracks.size, tracks, shuffle.inserted(this.tracks.size, tracks.size))
    }

    override fun insertTracks(index: Int, tracks: List<Track>) {
        commands += "insert $index ${tracks.map { it.id }}"
        insert(index, tracks, shuffle.insertedAfter(index, tracks.size))
    }

    override fun insertHead(index: Int, tracks: List<Track>, after: Int) {
        commands += "insert head $index ${tracks.map { it.id }} after $after"
        insert(index, tracks, shuffle.joined(index, tracks.size, currentIndex, after, joinRandom))
    }

    private fun insert(index: Int, added: List<Track>, order: HeadShuffle) {
        val wasEmpty = tracks.isEmpty()
        tracks.addAll(index, added)
        shuffle = order
        if (!wasEmpty && index <= currentIndex) currentIndex += added.size
        if (wasEmpty && tracks.isNotEmpty()) {
            currentIndex = 0
            positionMs = 0
            isEnded = false
            report(tracks[0], Transition.NEW_QUEUE)
        }
    }

    override fun removeAt(index: Int) {
        commands += "remove $index"
        val removedCurrent = index == currentIndex
        val following = if (removedCurrent) followingInOrder(index) else null
        val ends = removedCurrent && following == null
        tracks.removeAt(index)
        shuffle = shuffle.removed(index, index + 1)
        if (index < currentIndex) currentIndex--
        if (!removedCurrent) return
        currentIndex = following?.let { if (it > index) it - 1 else it } ?: playOrder().firstOrNull() ?: 0
        positionMs = 0
        isEnded = ends
        report(tracks.getOrNull(currentIndex), Transition.NEW_QUEUE, playing && !ends)
        if (ends) {
            controller.onEnded()
            if (playing) stopPlaying()
        }
    }

    override fun reload(index: Int) {
        commands += "reload $index"
        check(index in tracks.indices && index != currentIndex) {
            "Cannot reload $index: the playlist has ${tracks.size} items and $currentIndex is current"
        }
    }

    private fun followingInOrder(index: Int): Int? {
        val order = playOrder()
        val next = order.getOrNull(order.indexOf(index) + 1) ?: order.firstOrNull()?.takeIf { repeatEnabled }
        return next?.takeIf { it != index }
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

    private fun report(track: Track?, transition: Transition, isPlaying: Boolean = playing) {
        check(shuffle.length == tracks.size) {
            "The shuffle order has ${shuffle.length} items, the playlist ${tracks.size}: $track"
        }
        controller.onItemChanged(track, transition, isPlaying)
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
