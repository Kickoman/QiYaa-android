package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.WaveContext
import io.github.kickoman.qiyaa.yandex.WaveEvent
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QueueState(
    val entries: List<QueueEntry> = emptyList(),
    val title: String = "",
    val isWave: Boolean = false,
    val loadingMore: Boolean = false,
    val selected: Set<Int> = emptySet(),
    val activeSourceId: String? = null,
    val continueWithVibe: Boolean = false,
) {
    val tracks: List<Track> = entries.map(QueueEntry::track)
}

data class PlayModes(val shuffle: Boolean = false, val repeat: Boolean = false, val locked: Boolean = false)

/** Where playback would resume: for the notification's "play" after the process was killed. */
data class ResumePoint(val tracks: List<Track>, val index: Int, val positionMs: Long)

private data class RestorePoint(val index: Int, val positionMs: Long, val playWhenReady: Boolean)

class QueueController(
    private val source: MusicSource,
    private val connectivity: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val newPlayId: () -> String = { UUID.randomUUID().toString() },
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val store: QueueStore? = null,
    private val logFailure: (Stage, Throwable) -> Unit = { _, _ -> },
    private val continueWithVibeByDefault: () -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow(QueueState(continueWithVibe = continueWithVibeByDefault()))
    val state: StateFlow<QueueState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<QueueEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<QueueEvent> = mutableEvents.asSharedFlow()

    private val mutableActivity = MutableSharedFlow<QueueActivity>(extraBufferCapacity = ACTIVITY_BUFFER)
    val activity: SharedFlow<QueueActivity> = mutableActivity.asSharedFlow()

    @Volatile
    var engine: PlayerEngine? = null
        private set

    private var waveSessionId: String? = null
    private var queueGeneration = 0L
    private var sourceTicket = 0L
    private var loadJob: Job? = null
    private val playTracker = PlayTracker(clock)
    private var waveStationId = ""
    private var openWaveContext: WaveContext? = null
    private val feedback = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(io) {
            for (send in feedback) {
                try {
                    send()
                } catch (ignored: Exception) {
                    // Feedback is best-effort (TRK-11); a failure must not stop the ones after it.
                }
            }
        }
    }
    private var consecutiveTrackFailures = 0
    private var networkRetryAttempt = 0
    private var retryJob: Job? = null
    private var continueAtEnd = false
    private var restorePoint: RestorePoint? = null
    private val shuffleRule = WaveModeRule()
    private val repeatRule = WaveModeRule()
    private val mutableModes = MutableStateFlow(PlayModes())
    val modes: StateFlow<PlayModes> = mutableModes.asStateFlow()
    private val saves = Channel<String>(Channel.CONFLATED)
    private var waveSessionStale = false
    private var playing = false
    private var history = StartHistory()
    private var stalled = false
    private var asking = false
    private var stoppedAtEnd = false
    private var inHead = false
    private var waveSeedsVersion: Int? = null
    private var radioStartedPending: WaveContext? = null
    private var holding = false
    private var movingCursor = false

    private class JamRun(val listener: JamPlaybackListener, val waveFeedback: Boolean) {
        var hint: SeedHint? = null
        var reporter: Job? = null
    }

    private data class SessionStart(val seeds: List<String>, val version: Int?)

    private var jam: JamRun? = null

    val isJamActive: Boolean get() = jam != null

    init {
        store?.read()?.let(QueueSnapshotCodec::decode)?.let(::restore)
        if (store != null) {
            scope.launch(io) {
                for (text in saves) {
                    try {
                        store.write(text)
                    } catch (ignored: Exception) {
                        // A failed save keeps the previous file; the next change writes again.
                    }
                }
            }
        }
    }

    fun attach(engine: PlayerEngine) {
        this.engine = engine
        val restore = restorePoint ?: return
        restorePoint = null
        val state = mutableState.value
        if (state.tracks.isEmpty()) return
        applyModes()
        val index = restore.index.coerceIn(0, state.tracks.lastIndex)
        val head = headOf(state.entries)
        val lead = leadAt(state.entries, index)
        movingCursor = holding
        engine.setTracks(state.tracks, restore.playWhenReady, index, restore.positionMs, head, lead)
        movingCursor = false
    }

    fun detach(engine: PlayerEngine) {
        if (this.engine !== engine) return
        playTracker.closeAsSkip()?.let(::onClosed)
        if (mutableState.value.tracks.isNotEmpty()) {
            restorePoint =
                RestorePoint(engine.currentIndex, engine.positionMs.coerceAtLeast(0), playWhenReady = false)
        }
        this.engine = null
        save()
    }

    fun resumePoint(): ResumePoint? {
        val tracks = mutableState.value.tracks
        if (tracks.isEmpty()) return null
        val current = engine
        val restore = restorePoint
        val (index, positionMs) =
            when {
                current != null && current.itemCount > 0 -> current.currentIndex to current.positionMs
                restore != null -> restore.index to restore.positionMs
                else -> 0 to 0L
            }
        return ResumePoint(tracks, index.coerceIn(0, tracks.lastIndex), positionMs.coerceAtLeast(0))
    }

    fun next() {
        val current = engine ?: return
        if (holding) {
            releaseHeld(current)
            current.prepare()
            current.play()
            reportJam()
            return
        }
        val target = walkNext(current)
        val state = mutableState.value
        when {
            target != null -> {
                val wasStopped = stoppedAtEnd
                skipForward(current, target)
                if (wasStopped) {
                    current.prepare()
                    current.play()
                }
            }

            state.isWave -> {
                emit(QueueEvent.LoadingMore)
                requestMore()
            }

            state.continueWithVibe -> {
                stalled = false
                waitForMore(current)
                if (asking) emit(QueueEvent.LoadingMore) else stopAtEnd()
            }

            else -> stopAtEnd()
        }
        if (target == null) reportJam()
    }

    private fun stopAtEnd() {
        stop()
        stoppedAtEnd = true
    }

    private fun waitForMore(current: PlayerEngine) {
        continueAtEnd = true
        maybeLoadMore(current)
        if (!asking) continueAtEnd = false
        publishLoadingMore()
    }

    fun previous() {
        val current = engine ?: return
        if (jam != null) {
            current.seekToPosition(0)
            return
        }
        val head = wrappingHead(current)
        when {
            current.positionMs > RESTART_AFTER_MS -> current.seekToPosition(0)
            current.currentIndex == head.firstOrNull() -> wrapTo(head.last(), current)
            current.hasPrevious() -> current.skipToPrevious()
            else -> restart(current)
        }
    }

    fun onPausedAtEnd() {
        val current = engine ?: return
        val target = walkNext(current)
        if (target == naturalNext(current)) {
            current.play()
            return
        }
        apply(playTracker.onEnded())
        if (target == null) {
            onQueueEnd(current)
            return
        }
        wrapTo(target, current)
        current.play()
    }

    private fun skipForward(current: PlayerEngine, target: Int) {
        if (target == naturalNext(current)) current.skipToNext() else wrapTo(target, current)
    }

    private fun walkNext(current: PlayerEngine): Int? {
        if (current.itemCount == 0) return null
        val entries = mutableState.value.entries
        return QueueWalk.next(entries, current.playOrder(), current.currentIndex, repeatHoldsHead())
    }

    private fun naturalNext(current: PlayerEngine): Int? {
        if (current.itemCount == 0) return null
        val order = current.playOrder()
        val following = order.getOrNull(order.indexOf(current.currentIndex) + 1)
        return following ?: order.firstOrNull()?.takeIf { current.repeatEnabled }
    }

    private fun wrapTo(target: Int, current: PlayerEngine) {
        if (target == current.currentIndex) restart(current) else current.seekTo(target)
    }

    private fun wrappingHead(current: PlayerEngine): List<Int> {
        if (!repeatWaitsBehindHead()) return emptyList()
        val entries = mutableState.value.entries
        return current.playOrder().filter { entries.getOrNull(it)?.part == EntryPart.HEAD }
    }

    fun stop() {
        val current = engine ?: return
        stopWaiting()
        current.pause()
        restart(current)
    }

    private fun stopWaiting() {
        continueAtEnd = false
        publishLoadingMore()
    }

    private fun restart(current: PlayerEngine) {
        current.seekToPosition(0)
        apply(playTracker.onRestart())
    }

    private fun apply(change: PlayTracker.Change) {
        change.closed?.let(::onClosed)
        change.started?.let(::onStarted)
    }

    private fun onStarted(track: Track) {
        mutableActivity.tryEmit(QueueActivity.TrackStarted)
        if (jam == null) reportPlay(track)
        val entry = currentEntry()?.takeIf { it.track.id == track.id }
        val context = entry?.takeIf(::learnsFrom)?.wave
        openWaveContext = context
        if (context != null) sendFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
        if (entry != null && entry.part != EntryPart.CONTINUATION) history.note(track.id)
        if (!mutableState.value.isWave) engine?.let(::maybeLoadMore)
    }

    private fun learnsFrom(entry: QueueEntry?): Boolean {
        val run = jam ?: return true
        return run.waveFeedback && isHistoryVibe(entry)
    }

    private fun isHistoryVibe(entry: QueueEntry?): Boolean =
        entry?.part == EntryPart.CONTINUATION && !mutableState.value.isWave

    private fun currentEntry(): QueueEntry? {
        val current = engine ?: return null
        if (current.itemCount == 0) return null
        return mutableState.value.entries.getOrNull(current.currentIndex)
    }

    private fun onClosed(closed: PlayTracker.Closed) {
        val context = openWaveContext ?: return
        openWaveContext = null
        val event = if (closed.finished) WaveEvent.TRACK_FINISHED else WaveEvent.SKIP
        sendFeedback(context, event, closed.track, closed.playedSeconds)
    }

    private fun sendFeedback(context: WaveContext, event: WaveEvent, track: Track?, playedSeconds: Double) {
        feedback.trySend { source.waveFeedback(context, event, track, playedSeconds) }
    }

    fun onItemChanged(track: Track?, transition: Transition, isPlaying: Boolean) {
        val current = engine ?: return
        playing = isPlaying
        if (!movingCursor) holding = false
        val startedItem = if (track != null && !movingCursor) markStarted(current.currentIndex) else null
        if (track != null) {
            stoppedAtEnd = false
            stopWaiting()
            noteCursor(mutableState.value.entries.getOrNull(current.currentIndex))
            gatherWaiting(current)
            dropStaleTail()
        }
        applyModes()
        apply(playTracker.onItemChanged(track, transition, isPlaying && !movingCursor))
        save()
        maybeLoadMore(current)
        jam?.let { run ->
            startedItem?.let(run.listener::onItemStarted)
            reportJam()
        }
    }

    private fun noteCursor(entry: QueueEntry?) {
        when (entry?.part) {
            EntryPart.HEAD -> inHead = true
            EntryPart.CONTINUATION -> inHead = false
            else -> Unit
        }
    }

    private fun gatherWaiting(current: PlayerEngine) {
        val entries = mutableState.value.entries
        val index = current.currentIndex
        val waiting = entries.indices.filter { it != index && entries[it].isWaiting }
        if (waiting.isEmpty()) return
        val order = current.playOrder()
        val following = order.drop(order.indexOf(index) + 1).take(waiting.size)
        if (following == waiting && waiting == (index + 1..index + waiting.size).toList()) return
        val moved = waiting.map(entries::get)
        for (at in waiting.asReversed()) removeEntry(current, at)
        insertEntries(current, current.currentIndex + 1, moved)
    }

    private fun markStarted(index: Int): String? {
        val entry = mutableState.value.entries.getOrNull(index)
        val item = entry?.item?.takeIf { it.waiting } ?: return null
        val started = entry.copy(item = item.copy(waiting = false))
        mutableState.update { state ->
            state.copy(entries = state.entries.toMutableList().apply { set(index, started) })
        }
        return item.itemId
    }

    private fun releaseHeld(current: PlayerEngine) {
        if (!holding || movingCursor) return
        holding = false
        markStarted(current.currentIndex)?.let { itemId -> jam?.listener?.onItemStarted(itemId) }
        save()
    }

    fun onEnded() {
        apply(playTracker.onEnded())
        val current = engine ?: return
        onQueueEnd(current)
    }

    private fun onQueueEnd(current: PlayerEngine) {
        if (continuationFollows()) waitForMore(current)
        if (!continueAtEnd) stoppedAtEnd = true
        reportJam()
    }

    private fun modesLocked(): Boolean = mutableState.value.isWave

    fun setShuffle(on: Boolean) {
        val wasOn = shuffleRule.playerModeFor(modesLocked())
        shuffleRule.onUserChanged(on, modesLocked())
        if (!wasOn && shuffleRule.playerModeFor(modesLocked())) startShufflePass()
        applyModes()
        save()
    }

    private fun startShufflePass() {
        val current = engine ?: return
        val entries = mutableState.value.entries
        val lead = if (current.itemCount > 0) leadAt(entries, current.currentIndex) else emptyList()
        current.shuffleHead(headOf(entries), lead)
    }

    private fun headOf(entries: List<QueueEntry>): List<Int> =
        entries.indices.filter { entries[it].part == EntryPart.HEAD }

    private fun leadAt(entries: List<QueueEntry>, index: Int): List<Int> {
        if (!inHead || index !in entries.indices) return emptyList()
        return listOf(index) + entries.indices.filter { it != index && entries[it].isWaiting }
    }

    fun setRepeat(on: Boolean) {
        repeatRule.onUserChanged(on, modesLocked())
        applyModes()
        save()
    }

    private fun applyModes() {
        publishModes()
        val current = engine ?: return
        current.shuffleEnabled = shuffleRule.playerModeFor(modesLocked())
        val continued = mutableState.value.entries.any { it.part == EntryPart.CONTINUATION }
        current.repeatEnabled = repeatHoldsHead() && !continued
        current.pauseAtEndOfCurrent = walkNext(current) != naturalNext(current)
    }

    private fun publishModes() {
        mutableModes.value = PlayModes(shuffleRule.wanted, repeatRule.wanted, modesLocked())
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        mutableActivity.tryEmit(QueueActivity.PlayingChanged(isPlaying))
        playing = isPlaying
        if (isPlaying) {
            if (consecutiveTrackFailures > 0) stopWaiting()
            resetFailures()
            stoppedAtEnd = false
            engine?.let(::releaseHeld)
        }
        playTracker.onPlayingChanged(isPlaying)?.let(::onStarted)
        if (!isPlaying) save()
        reportJam()
    }

    fun onSeeked() {
        stopWaiting()
        reportJam()
        save()
    }

    fun onFailure(kind: FailureKind, error: ErrorKind) {
        val waiting = kind == FailureKind.NETWORK && networkRetryAttempt > 0
        if (!waiting) mutableActivity.tryEmit(QueueActivity.PlaybackFailed(kind))
        val current = engine ?: return
        val target = walkNext(current)
        when (
            ErrorPolicy.decide(
                kind,
                consecutiveTrackFailures,
                target != null,
                continuationFollows(),
            )
        ) {
            ErrorAction.WaitForNetwork -> waitForNetwork(current)

            ErrorAction.Hold -> emit(QueueEvent.Failed(Stage.PLAYBACK, error))

            ErrorAction.SkipToNext -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, error))
                target?.let { skipForward(current, it) }
                current.prepare()
                current.play()
            }

            ErrorAction.WaitForMore -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, error))
                waitForMore(current)
                if (!asking) stoppedAtEnd = true
                reportJam()
            }

            ErrorAction.Stop -> {
                emit(QueueEvent.StoppedAfterFailures(consecutiveTrackFailures + 1))
                consecutiveTrackFailures = 0
                if (target == null) stoppedAtEnd = true
                reportJam()
            }
        }
    }

    fun requestMore() {
        val current = engine ?: return
        if (!mutableState.value.isWave) return
        waitForMore(current)
    }

    fun setContinueWithVibe(on: Boolean) {
        val state = mutableState.value
        if (state.isWave || state.continueWithVibe == on) return
        mutableState.update { it.copy(continueWithVibe = on) }
        if (on) {
            stalled = false
            engine?.let(::maybeLoadMore)
        }
        save()
    }

    private fun continuationFollows(): Boolean {
        val state = mutableState.value
        return when {
            state.isWave -> true
            !state.continueWithVibe || stalled -> false
            else -> asking || waveSessionId != null || history.seeds().isNotEmpty() || jamSeeds().isNotEmpty()
        }
    }

    fun loadSource(
        title: String,
        sourceId: String? = null,
        autoplay: Boolean = true,
        loader: suspend () -> List<Track>,
    ) {
        val ticket = newSourceRequest()
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val tracks =
                    try {
                        withContext(io) { loader() }
                    } catch (failed: Exception) {
                        if (isLatest(ticket)) fail(Stage.SOURCE, failed)
                        return@launch
                    }
                if (!isLatest(ticket)) return@launch
                if (tracks.none { it.available }) {
                    emit(QueueEvent.SourceEmpty(title))
                    return@launch
                }
                setQueue(tracks, title, isWave = false, autoplay = autoplay, sourceId = sourceId)
                emit(QueueEvent.SourceLoaded(title, tracks.count { it.available }))
            }
    }

    fun playWave(seeds: List<String>, title: String, sourceId: String = seeds.first()) {
        val ticket = newSourceRequest()
        loadJob?.cancel()
        emit(QueueEvent.SourceLoading(title))
        loadJob =
            scope.launch {
                val batch =
                    try {
                        withContext(io) { source.startWave(seeds) }
                    } catch (failed: Exception) {
                        if (isLatest(ticket)) fail(Stage.WAVE, failed)
                        return@launch
                    }
                if (!isLatest(ticket)) return@launch
                if (batch.tracks.none { it.available }) {
                    emit(QueueEvent.SourceEmpty(title))
                    return@launch
                }
                waveSessionId = batch.sessionId
                waveSessionStale = false
                waveStationId = seeds.first()
                val context = WaveContext(batch.sessionId, waveStationId, batch.batchId)
                announce(context, historyVibe = false)
                val entries = batch.tracks.map { QueueEntry(it, EntryPart.CONTINUATION, context) }
                replaceQueue(entries, title, isWave = true, autoplay = true, sourceId = sourceId)
                emit(QueueEvent.WaveStarted(title))
            }
    }

    fun search(text: String, title: String) {
        val ticket = newSourceRequest()
        loadJob?.cancel()
        emit(QueueEvent.SearchStarted(title))
        loadJob =
            scope.launch {
                try {
                    val result = withContext(io) { source.search(text) }
                    if (!isLatest(ticket)) return@launch
                    val (tracks, name) =
                        withContext(io) {
                            when {
                                result.bestType == "artist" && result.bestId.isNotEmpty() ->
                                    source.artistTopTracks(result.bestId) to result.bestName

                                result.bestType == "album" && result.bestId.isNotEmpty() ->
                                    source.albumTracks(result.bestId) to result.bestName

                                else -> result.tracks to title
                            }
                        }
                    if (!isLatest(ticket)) return@launch
                    if (tracks.none { it.available }) {
                        emit(QueueEvent.NothingFound)
                        return@launch
                    }
                    setQueue(tracks, name, isWave = false, autoplay = true, sourceId = null)
                    emit(QueueEvent.SourceLoaded(name, tracks.count { it.available }))
                } catch (failed: Exception) {
                    if (isLatest(ticket)) fail(Stage.SEARCH, failed)
                }
            }
    }

    fun setQueue(tracks: List<Track>, title: String, isWave: Boolean, autoplay: Boolean, sourceId: String?) {
        val part = EntryPart.defaultFor(isWave)
        replaceQueue(tracks.map { QueueEntry(it, part) }, title, isWave, autoplay, sourceId)
    }

    private fun replaceQueue(
        entries: List<QueueEntry>,
        title: String,
        isWave: Boolean,
        autoplay: Boolean,
        sourceId: String?,
    ) {
        if (jam != null) {
            replaceBase(entries, title, isWave, sourceId)
            return
        }
        val current = engine
        playTracker.closeAsSkip()?.let(::onClosed)
        queueGeneration++
        resetFailures()
        forgetContinuation()
        stoppedAtEnd = false
        holding = false
        val playable = entries.filter { it.track.available }
        if (!isWave) {
            waveSessionId = null
            waveStationId = ""
        }
        mutableState.value =
            QueueState(
                entries = playable,
                title = title,
                isWave = isWave,
                activeSourceId = sourceId,
                continueWithVibe = continueWithVibeByDefault(),
            )
        inHead = playable.any { it.part == EntryPart.HEAD }
        applyModes()
        if (current == null) {
            restorePoint = RestorePoint(0, 0, playWhenReady = autoplay)
            save()
            return
        }
        restorePoint = null
        val tracks = playable.map(QueueEntry::track)
        current.setTracks(tracks, autoplay, head = headOf(playable), lead = leadAt(playable, 0))
        save()
    }

    private fun replaceBase(entries: List<QueueEntry>, title: String, isWave: Boolean, sourceId: String?) {
        val current = engine
        val before = mutableState.value.entries
        val dropsHeld = holding && before.getOrNull(cursorIndex(current))?.isWaiting == false
        val cursor = if (dropsHeld) -1 else cursorIndex(current)
        movingCursor = dropsHeld
        for (at in before.indices.reversed()) {
            if (at != cursor && !before[at].isJamItem) removeEntry(current, at)
        }
        movingCursor = false
        val cursorAt = before.indices.count { it < cursor && before[it].isJamItem }
        mutableState.update { state ->
            val rows = state.entries.mapIndexed { i, entry ->
                val other = cursor >= 0 && i == cursorAt && entry.part != EntryPart.ITEM
                if (other) entry.copy(part = EntryPart.OTHER) else entry
            }
            state.copy(
                entries = rows,
                title = title,
                isWave = isWave,
                selected = emptySet(),
                activeSourceId = sourceId,
                continueWithVibe = continueWithVibeByDefault(),
            )
        }
        queueGeneration++
        val waited = continueAtEnd
        forgetContinuation()
        if (waited && !playing) stoppedAtEnd = true
        if (!isWave) {
            waveSessionId = null
            waveStationId = ""
        }
        val playable = entries.filter { it.track.available }
        inHead = playable.any { it.part == EntryPart.HEAD }
        if (cursor < 0) holding = playable.isNotEmpty()
        if (playable.isNotEmpty()) {
            mutableState.update { it.copy(entries = it.entries + playable) }
            movingCursor = holding
            current?.appendTracks(playable.map(QueueEntry::track))
            movingCursor = false
        }
        val rows = mutableState.value.entries
        if (current != null && current.itemCount > 0 && shuffleRule.playerModeFor(modesLocked())) {
            current.shuffleHead(headOf(rows), leadAt(rows, current.currentIndex))
        }
        if (current == null) {
            val point = restorePoint
            restorePoint =
                if (cursor < 0) {
                    RestorePoint(0, 0, playWhenReady = false)
                } else {
                    RestorePoint(cursorAt, point?.positionMs ?: 0, point?.playWhenReady ?: false)
                }
        }
        applyModes()
        publishLoadingMore()
        save()
        reportJam()
    }

    private fun forgetContinuation() {
        continueAtEnd = false
        asking = false
        stalled = false
        waveSessionStale = false
        waveSeedsVersion = null
        history.clear()
    }

    private fun appendEntries(entries: List<QueueEntry>) {
        val playable = entries.filter { it.track.available }
        if (playable.isEmpty()) return
        mutableState.update { it.copy(entries = it.entries + playable) }
        engine?.appendTracks(playable.map(QueueEntry::track))
        applyModes()
        save()
    }

    fun clear() {
        if (jam != null) {
            newSourceRequest()
            replaceBase(emptyList(), title = "", isWave = false, sourceId = null)
            return
        }
        playTracker.closeAsSkip()?.let(::onClosed)
        queueGeneration++
        resetFailures()
        forgetContinuation()
        stoppedAtEnd = false
        holding = false
        restorePoint = null
        waveSessionId = null
        waveStationId = ""
        inHead = false
        mutableState.value = QueueState(continueWithVibe = continueWithVibeByDefault())
        publishModes()
        save()
        val current = engine ?: return
        current.stop()
        current.clear()
    }

    fun removeIndices(indices: Set<Int>) {
        if (indices.isEmpty()) return
        val entries = mutableState.value.entries
        val chosen = indices.filter { it in entries.indices }.toSet()
        mutableState.update { it.copy(selected = emptySet()) }
        val items = if (jam == null) emptyList() else chosen.filter { entries[it].isJamItem }.sorted()
        if (items.isNotEmpty()) jam?.listener?.onRemoveItems(items.mapNotNull { entries[it].item?.itemId })
        val removed = chosen - items.toSet()
        val current = engine
        if (current != null && current.itemCount > 0 && current.currentIndex in removed) {
            removeCurrent(current, removed, leaving = chosen)
        } else {
            for (at in removed.sortedDescending()) removeEntry(current, at)
        }
        applyModes()
        save()
        reportJam()
    }

    private fun removeCurrent(current: PlayerEngine, removed: Set<Int>, leaving: Set<Int>) {
        val index = current.currentIndex
        val entries = mutableState.value.entries
        val staying = entries.filterIndexed { i, _ -> i !in leaving }
        val target = QueueWalk.next(entries, current.playOrder(), index, repeatHoldsHead(staying), leaving)
            ?.let(entries::get)
        val stops = jam != null
        movingCursor = true
        holding = stops
        if (stops) current.pause()
        playTracker.closeAsSkip()?.let(::onClosed)
        for (at in removed.sortedDescending()) if (at != index) removeEntry(current, at)
        removeEntry(current, index - removed.count { it < index })
        val at = mutableState.value.entries.indexOfFirst { it === target }
        if (at >= 0) {
            if (current.currentIndex != at || current.isEnded) current.seekTo(at)
            stoppedAtEnd = false
        } else if (current.itemCount > 0) {
            val end = current.playOrder().last()
            if (current.currentIndex != end) current.seekTo(end)
            stopAtEnd()
        }
        movingCursor = false
        holding = stops && currentEntry()?.isWaiting == true
        if (stops || current.itemCount == 0) return
        markStarted(current.currentIndex)
        if (playing) playTracker.onPlayingChanged(true)?.let(::onStarted)
    }

    fun toggleSelected(index: Int) = mutableState.update { state ->
        state.copy(
            selected = if (index in
                state.selected
            ) {
                state.selected - index
            } else {
                state.selected + index
            },
        )
    }

    fun selectAllOrNone() = mutableState.update { state ->
        val all = state.selected.size == state.tracks.size
        state.copy(selected = if (all) emptySet() else state.tracks.indices.toSet())
    }

    fun toggleLike(track: Track) {
        setLiked(track, !source.isLiked(track.id))
    }

    fun setLiked(track: Track, liked: Boolean) {
        scope.launch {
            try {
                withContext(io) { source.setLiked(track.id, liked) }
                emit(QueueEvent.LikeChanged(liked = liked))
            } catch (failed: Exception) {
                fail(Stage.LIKE, failed)
            }
        }
    }

    fun dislike(track: Track) {
        val skips = !holding && currentTrack()?.id == track.id
        if (skips) dislikeAndSkip(track) else sendDislike(track, QueueEvent.Disliked)
    }

    private fun dislikeAndSkip(track: Track) {
        sendDislike(track, QueueEvent.DislikedAndSkipped)
        next()
    }

    private fun sendDislike(track: Track, done: QueueEvent) {
        scope.launch {
            try {
                withContext(io) { source.dislike(track.id) }
                emit(done)
            } catch (failed: Exception) {
                fail(Stage.LIKE, failed)
            }
        }
    }

    fun currentWaveSeed(): String? =
        waveStationId.takeIf { mutableState.value.isWave && waveSessionId != null && it.isNotEmpty() }

    fun likeCurrent() {
        currentTrack()?.let(::toggleLike)
    }

    fun dislikeCurrent() {
        currentTrack()?.let(::dislike)
    }

    private fun currentTrack(): Track? = currentEntry()?.track

    private fun waitForNetwork(current: PlayerEngine) {
        if (retryJob?.isActive == true) return
        if (networkRetryAttempt == 0) emit(QueueEvent.WaitingForNetwork)
        val attempt = networkRetryAttempt++
        retryJob =
            scope.launch {
                ErrorPolicy.awaitRetry(connectivity, attempt)
                if (engine === current) current.prepare()
            }
    }

    private fun resetFailures() {
        retryJob?.cancel()
        consecutiveTrackFailures = 0
        networkRetryAttempt = 0
    }

    private fun emit(event: QueueEvent) {
        mutableEvents.tryEmit(event)
    }

    private fun fail(stage: Stage, failed: Exception) {
        logFailure(stage, failed)
        emit(QueueEvent.Failed(stage, ErrorKind.of(failed)))
    }

    private fun newSourceRequest(): Long = ++sourceTicket

    private fun isLatest(ticket: Long) = ticket == sourceTicket

    private fun reportPlay(track: Track) {
        scope.launch(io) {
            try {
                source.reportPlayStarted(track, newPlayId())
            } catch (ignored: Exception) {
                // Listen marks are best-effort; the desktop app drops them the same way.
            }
        }
    }

    private fun restore(snapshot: QueueSnapshot) {
        if (snapshot.tracks.isEmpty()) return
        val default = EntryPart.defaultFor(snapshot.isWave)
        val entries =
            snapshot.tracks.mapIndexed { i, track ->
                val item = snapshot.items.getOrNull(i)
                val stored = snapshot.parts.getOrNull(i)?.takeIf { it != EntryPart.ITEM } ?: default
                val part = if (item != null) EntryPart.ITEM else stored
                val batchId = snapshot.batchIds.getOrElse(i) { "" }
                val wave =
                    if (part == EntryPart.CONTINUATION && snapshot.waveSessionId.isNotEmpty()) {
                        WaveContext(snapshot.waveSessionId, snapshot.waveStationId, batchId)
                    } else {
                        null
                    }
                QueueEntry(track, part, wave, item, snapshot.seedsVersions.getOrNull(i))
            }
        inHead = inHeadAt(entries, snapshot.index)
        holding = entries.getOrNull(snapshot.index)?.isWaiting == true
        mutableState.value =
            QueueState(
                entries = entries,
                title = snapshot.title,
                isWave = snapshot.isWave,
                activeSourceId = snapshot.sourceId,
                continueWithVibe = snapshot.continueWithVibe ?: continueWithVibeByDefault(),
            )
        shuffleRule.onUserChanged(snapshot.shuffle, locked = false)
        repeatRule.onUserChanged(snapshot.repeat, locked = false)
        history = StartHistory(snapshot.history)
        if (snapshot.waveSessionId.isNotEmpty()) {
            waveSessionId = snapshot.waveSessionId
            waveStationId = snapshot.waveStationId
            waveSessionStale = true
            waveSeedsVersion = entries.lastOrNull { it.part == EntryPart.CONTINUATION }?.seedsVersion
        }
        restorePoint = RestorePoint(snapshot.index, snapshot.positionMs, playWhenReady = false)
        publishModes()
    }

    private fun inHeadAt(entries: List<QueueEntry>, index: Int): Boolean {
        val base = entries.take(index + 1).lastOrNull {
            it.part == EntryPart.HEAD || it.part == EntryPart.CONTINUATION
        }
        return base?.part == EntryPart.HEAD || (base == null && entries.any { it.part == EntryPart.HEAD })
    }

    private fun startSession(batch: WaveBatch, start: SessionStart, historyVibe: Boolean) {
        waveSessionStale = false
        waveSessionId = batch.sessionId
        waveStationId = start.seeds.first()
        waveSeedsVersion = start.version
        announce(WaveContext(batch.sessionId, waveStationId, batch.batchId), historyVibe)
    }

    private fun announce(context: WaveContext, historyVibe: Boolean) {
        val run = jam
        if (run == null || (historyVibe && run.waveFeedback)) {
            sendFeedback(context, WaveEvent.RADIO_STARTED, null, 0.0)
        } else {
            radioStartedPending = context
        }
    }

    private fun save() {
        if (store == null) return
        val state = mutableState.value
        val point = resumePoint()
        val parts = state.entries.map(QueueEntry::part)
        val items = state.entries.map(QueueEntry::item)
        val seedsVersions = state.entries.map(QueueEntry::seedsVersion)
        val snapshot =
            QueueSnapshot(
                tracks = state.tracks,
                batchIds = state.entries.map { it.wave?.batchId.orEmpty() },
                title = state.title,
                sourceId = state.activeSourceId,
                isWave = state.isWave,
                waveSessionId = waveSessionId.orEmpty(),
                waveStationId = if (waveSessionId != null) waveStationId else "",
                index = point?.index ?: 0,
                positionMs = point?.positionMs ?: 0,
                shuffle = shuffleRule.wanted,
                repeat = repeatRule.wanted,
                parts = if (parts.all { it == EntryPart.defaultFor(state.isWave) }) emptyList() else parts,
                items = if (items.all { it == null }) emptyList() else items,
                seedsVersions = if (seedsVersions.all { it == null }) emptyList() else seedsVersions,
                continueWithVibe = state.continueWithVibe,
                history = history.ids,
            )
        saves.trySend(QueueSnapshotCodec.encode(snapshot))
    }

    private fun maybeLoadMore(current: PlayerEngine) {
        if (asking) return
        val state = mutableState.value
        val own = state.isWave
        val session = waveSessionId
        if (own && session == null) return
        if (!own && (!state.continueWithVibe || stalled)) return
        if (remaining(current) > LOAD_MORE_WHEN_LEFT) return
        val fits = own || sessionFitsHint()
        val start = if (session == null || waveSessionStale || !fits) newSessionStart(own) else null
        val recent = state.tracks.takeLast(WAVE_HISTORY).map { it.id }
        val version = start?.version ?: waveSeedsVersion
        val request: suspend () -> WaveBatch =
            when {
                start != null -> {
                    { source.startWave(start.seeds) }
                }

                session != null && fits -> {
                    { source.moreWave(session, recent) }
                }

                else -> return
            }
        val first = !own && (session == null || !fits)
        val stage = if (first) Stage.WAVE else Stage.WAVE_MORE
        val generation = queueGeneration
        asking = true
        publishLoadingMore()
        scope.launch {
            val batch: WaveBatch? =
                try {
                    withContext(io) { request() }
                } catch (failed: Exception) {
                    logFailure(stage, failed)
                    val shown = generation == queueGeneration && (own || !outdated(version))
                    if (shown) emit(QueueEvent.Failed(stage, ErrorKind.of(failed)))
                    null
                }
            if (generation != queueGeneration) return@launch
            asking = false
            if (!own && outdated(version)) {
                onOutdatedReply()
                return@launch
            }
            val resume = continueAtEnd
            continueAtEnd = false
            publishLoadingMore()
            val available = batch?.tracks?.filter { it.available }.orEmpty()
            if (batch == null || available.isEmpty()) {
                if (first) stalled = true
                if (resume) stoppedAtEnd = true
                return@launch
            }
            if (start != null) startSession(batch, start, historyVibe = !own)
            val sessionId = batch.sessionId.ifEmpty { session.orEmpty() }
            val context = WaveContext(sessionId, waveStationId, batch.batchId)
            val firstNew = mutableState.value.entries.size
            appendEntries(available.map { QueueEntry(it, EntryPart.CONTINUATION, context, null, version) })
            val afterAppend = engine ?: return@launch
            if (resume) {
                afterAppend.seekTo(firstNew)
                afterAppend.prepare()
                afterAppend.play()
            }
        }
    }

    private fun onOutdatedReply() {
        engine?.let(::maybeLoadMore)
        if (!asking && continueAtEnd) {
            continueAtEnd = false
            stoppedAtEnd = true
            reportJam()
        }
        publishLoadingMore()
    }

    private fun sessionFitsHint(): Boolean {
        val hint = jam?.hint ?: return waveSeedsVersion == null
        return hint.seeds.isEmpty() || waveSeedsVersion == hint.version
    }

    private fun outdated(version: Int?): Boolean {
        val hint = jam?.hint ?: return version != null
        return hint.seeds.isNotEmpty() && version != hint.version
    }

    private fun jamSeeds(): List<String> = jam?.hint?.seeds.orEmpty()

    private fun newSessionStart(own: Boolean): SessionStart? {
        val station = listOfNotNull(waveStationId.takeIf { waveSessionStale && it.isNotEmpty() })
        val hint = jam?.hint
        val seeds: List<String>
        val version: Int?
        when {
            own -> {
                seeds = station
                version = null
            }

            hint != null && hint.seeds.isNotEmpty() -> {
                seeds = hint.seeds
                version = hint.version
            }

            else -> {
                seeds = history.seeds().ifEmpty { station }
                version = null
            }
        }
        return SessionStart(seeds, version).takeIf { seeds.isNotEmpty() }
    }

    private fun remaining(current: PlayerEngine): Int {
        val entries = mutableState.value.entries
        return QueueWalk.remaining(entries, current.playOrder(), current.currentIndex, repeatHoldsHead())
    }

    private fun repeatHoldsHead(entries: List<QueueEntry> = mutableState.value.entries): Boolean {
        val inAHead = entries.isEmpty() || (inHead && entries.any { it.part == EntryPart.HEAD })
        return repeatRule.playerModeFor(modesLocked()) && inAHead
    }

    private fun repeatWaitsBehindHead(): Boolean =
        repeatHoldsHead() && mutableState.value.entries.any { it.part == EntryPart.CONTINUATION }

    private fun publishLoadingMore() {
        mutableState.update { it.copy(loadingMore = asking && (it.isWave || continueAtEnd)) }
    }

    fun startJam(listener: JamPlaybackListener, waveFeedback: Boolean, afterRestart: Boolean = false) {
        if (jam != null) return
        if (afterRestart) replaceBase(emptyList(), title = "", isWave = false, sourceId = null)
        val run = JamRun(listener, waveFeedback)
        jam = run
        if (!learnsFrom(currentEntry())) openWaveContext = null
        applyModes()
        run.reporter =
            scope.launch {
                while (true) {
                    delay(PLAYING_REPORT_MS)
                    if (playing) reportJam()
                }
            }
        reportJam()
        save()
    }

    fun onJamQueue(entries: List<JamEntry>, seeds: List<String>, seedsVersion: Int) {
        val run = jam ?: return
        if (seeds.isNotEmpty() && seedsVersion != run.hint?.version) stalled = false
        run.hint = SeedHint(seeds, seedsVersion)
        val idle = !holding && nothingToPlay()
        val nextBefore = nextTrack()
        val trackBefore = currentEntry()?.track
        val current = engine
        val hadRows = mutableState.value.entries.isNotEmpty()
        val engineWasEmpty = current?.itemCount == 0
        editJamPart(current, entries)
        dropStaleTail()
        if (current == null) {
            val filled = !hadRows && mutableState.value.entries.isNotEmpty()
            if (filled) restorePoint = RestorePoint(0, 0, playWhenReady = true)
        } else if (idle) {
            startWaiting(current, engineWasEmpty)
        } else if (!current.isEnded && !stoppedAtEnd && !holding) {
            maybeLoadMore(current)
        }
        applyModes()
        save()
        if (nextTrack() != nextBefore || currentEntry()?.track != trackBefore) reportJam()
    }

    private fun startWaiting(current: PlayerEngine, wasEmpty: Boolean) {
        if (current.itemCount == 0) return
        if (wasEmpty) {
            current.prepare()
            current.play()
            return
        }
        val waiting = firstWaiting(current.currentIndex) ?: return
        stopWaiting()
        current.seekTo(waiting)
        current.prepare()
        current.play()
    }

    private fun firstWaiting(current: Int): Int? {
        val entries = mutableState.value.entries
        return entries.indices.firstOrNull { it != current && entries[it].isWaiting }
    }

    private fun editJamPart(current: PlayerEngine?, wanted: List<JamEntry>) {
        val entries = mutableState.value.entries
        val index = cursorIndex(current)
        val currentItemId = entries.getOrNull(index)?.item?.itemId
        val ahead = holding && entries.getOrNull(index)?.isWaiting == false
        val tailAfter = if (ahead) index - 1 else index
        val edit = JamTail.edit(entries, tailAfter, wanted.filterNot { it.itemId == currentItemId })
        for (at in edit.removeUntil - 1 downTo edit.removeFrom) removeEntry(current, at)
        insertEntries(current, edit.insertAt, edit.insert.map(::waitingItem))
        if (ahead && edit.insert.isNotEmpty() && current != null) holdFirstItem(current, edit.insertAt)
        val dropsHeld = holding && currentItemId != null && wanted.none { it.itemId == currentItemId }
        if (!dropsHeld) return
        if (current != null) {
            removeCurrent(current, setOf(index), setOf(index))
            return
        }
        removeEntry(null, index)
        restorePoint = restorePoint?.copy(positionMs = 0)
        holding = mutableState.value.entries.getOrNull(index)?.isWaiting == true
    }

    private fun holdFirstItem(current: PlayerEngine, index: Int) {
        movingCursor = true
        current.seekTo(index)
        movingCursor = false
        if (shuffleRule.playerModeFor(modesLocked())) startShufflePass()
    }

    private fun cursorIndex(current: PlayerEngine?): Int {
        val entries = mutableState.value.entries
        if (entries.isEmpty()) return -1
        if (current != null) return if (current.itemCount == 0) -1 else current.currentIndex
        return restorePoint?.index?.coerceIn(0, entries.lastIndex) ?: 0
    }

    fun jamSkip(itemId: String) {
        val current = engine ?: return
        val item = mutableState.value.entries.getOrNull(current.currentIndex)?.item
        if (jam != null && !holding && item?.itemId == itemId) next()
    }

    fun endJam() {
        val run = jam
        jam = null
        run?.reporter?.cancel()
        mutableState.update { state ->
            state.copy(entries = state.entries.map { it.withoutItemId() })
        }
        val current = engine
        val entries = mutableState.value.entries
        for (at in entries.lastIndex downTo cursorIndex(current) + 1) {
            val party = entries[at].part == EntryPart.CONTINUATION && entries[at].seedsVersion != null
            if (party) removeEntry(current, at)
        }
        if (waveSeedsVersion != null) {
            waveSessionId = null
            waveStationId = ""
            waveSessionStale = false
            waveSeedsVersion = null
        }
        radioStartedPending?.takeIf { it.sessionId == waveSessionId }?.let {
            sendFeedback(it, WaveEvent.RADIO_STARTED, null, 0.0)
        }
        radioStartedPending = null
        applyModes()
        save()
    }

    private fun QueueEntry.withoutItemId(): QueueEntry {
        val mark = item?.takeIf { it.itemId != null } ?: return this
        return copy(item = mark.copy(itemId = null))
    }

    private fun dropStaleTail() {
        val hint = jam?.hint ?: return
        val entries = mutableState.value.entries
        if (hint.seeds.isEmpty() || mutableState.value.isWave || entries.any { it.isWaiting }) return
        val current = engine
        val index = cursorIndex(current)
        if (isHistoryVibe(entries.getOrNull(index))) return
        val stale = (index + 1..entries.lastIndex).filter {
            entries[it].part == EntryPart.CONTINUATION && entries[it].seedsVersion != hint.version
        }
        for (at in stale.asReversed()) removeEntry(current, at)
    }

    private fun waitingItem(entry: JamEntry): QueueEntry =
        QueueEntry(entry.track, EntryPart.ITEM, item = ItemMark(entry.itemId, entry.addedBy, waiting = true))

    private fun insertEntries(current: PlayerEngine?, index: Int, inserted: List<QueueEntry>) {
        if (inserted.isEmpty()) return
        mutableState.update { state ->
            val entries = state.entries.toMutableList().apply { addAll(index, inserted) }
            val selected = state.selected.map { if (it >= index) it + inserted.size else it }.toSet()
            state.copy(entries = entries, selected = selected)
        }
        current?.insertTracks(index, inserted.map(QueueEntry::track))
    }

    private fun removeEntry(current: PlayerEngine?, index: Int) {
        mutableState.update { state ->
            state.copy(
                entries = state.entries.filterIndexed { i, _ -> i != index },
                selected = state.selected.filter { it != index }.map { if (it > index) it - 1 else it }
                    .toSet(),
            )
        }
        current?.removeAt(index)
    }

    /** `onPlayback` for what plays now, as the host needs after `resumed` (HOST-26). */
    fun reportJamPlayback() = reportJam()

    private fun nothingToPlay(): Boolean {
        val current = engine ?: return true
        if (current.itemCount == 0 || mutableState.value.entries.isEmpty()) return true
        if (holding || current.isEnded || stoppedAtEnd) return true
        return continueAtEnd && walkNext(current) == null
    }

    private fun nextTrack(): Track? {
        val current = engine ?: return null
        return walkNext(current)?.let { mutableState.value.tracks.getOrNull(it) }
    }

    private fun reportJam() {
        val run = jam ?: return
        val entry = currentEntry()
        val playback =
            if (entry == null || nothingToPlay()) {
                JamPlayback(JamPlayback.Kind.IDLE, null, null, 0, paused = true)
            } else {
                val itemId = entry.item?.itemId
                JamPlayback(
                    if (itemId != null) JamPlayback.Kind.ITEM else JamPlayback.Kind.BASE,
                    itemId,
                    entry.track,
                    engine?.positionMs?.coerceAtLeast(0) ?: 0,
                    !playing,
                    nextTrack(),
                )
            }
        run.listener.onPlayback(playback)
    }

    companion object {
        const val MY_WAVE_SEED = "user:onyourwave"
        const val LOAD_MORE_WHEN_LEFT = 2
        const val WAVE_HISTORY = 5
        const val RESTART_AFTER_MS = 3_000L
        const val PLAYING_REPORT_MS = 10_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val EVENT_BUFFER = 8
        private const val ACTIVITY_BUFFER = 64
    }
}
