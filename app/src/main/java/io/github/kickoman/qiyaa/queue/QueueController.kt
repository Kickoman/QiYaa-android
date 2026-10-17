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
    val jamSlots: List<JamSlot>? = null,
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
    private val mutableState = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<QueueEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<QueueEvent> = mutableEvents.asSharedFlow()

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

    private class JamRun(val listener: JamPlaybackListener, val waveFeedback: Boolean) {
        var entries: List<JamEntry> = emptyList()
        var seeds: List<String> = emptyList()
        var seedsVersion = -1
        var wave: JamWave? = null
        var waveLoading = false
        var waitingForWave = false
        var reporter: Job? = null
    }

    private data class JamWave(val sessionId: String, val seedsVersion: Int, val stationId: String)

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
        val headSize = headSizeOf(state.entries)
        engine.setTracks(state.tracks, restore.playWhenReady, index, restore.positionMs, headSize)
        jam?.let(::applyJamQueue)
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
        jam?.let { run ->
            if (current.hasNext()) {
                current.skipToNext()
            } else {
                run.waitingForWave = true
                ensureJamWave(run)
            }
            return
        }
        val state = mutableState.value
        when {
            current.hasNext() -> {
                val wasStopped = stoppedAtEnd
                skipForward(current)
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
        val head = wrappingHead(current)
        if (current.currentIndex == head.lastOrNull()) {
            apply(playTracker.onEnded())
            wrapTo(head.first(), current)
        }
        current.play()
    }

    private fun skipForward(current: PlayerEngine) {
        val head = wrappingHead(current)
        if (current.currentIndex == head.lastOrNull()) wrapTo(head.first(), current) else current.skipToNext()
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
        if (jam == null) reportPlay(track)
        val entry = currentEntry()?.takeIf { it.track.id == track.id }
        val context = entry?.wave
        openWaveContext = context
        if (context != null) sendFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
        if (entry?.part == EntryPart.HEAD) history.note(track.id)
        if (!mutableState.value.isWave && jam == null) engine?.let(::maybeLoadMore)
    }

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
        if (track != null) {
            stoppedAtEnd = false
            stopWaiting()
        }
        applyModes()
        apply(playTracker.onItemChanged(track, transition, isPlaying))
        save()
        maybeLoadMore(current)
        jam?.let { run ->
            val slot = mutableState.value.jamSlots?.getOrNull(current.currentIndex)
            if (slot is JamSlot.Item && track != null) run.listener.onItemStarted(slot.itemId)
            if (track != null) run.waitingForWave = false
            reportJam()
            ensureJamWave(run)
        }
    }

    fun onEnded() {
        apply(playTracker.onEnded())
        val current = engine ?: return
        if (jam == null) {
            if (continuationFollows()) waitForMore(current)
            if (!continueAtEnd) stoppedAtEnd = true
        }
        jam?.let { run ->
            run.waitingForWave = true
            reportJam()
            ensureJamWave(run)
        }
    }

    private fun modesLocked(): Boolean = mutableState.value.isWave || jam != null

    fun setShuffle(on: Boolean) {
        val wasOn = shuffleRule.playerModeFor(modesLocked())
        shuffleRule.onUserChanged(on, modesLocked())
        if (!wasOn && shuffleRule.playerModeFor(modesLocked())) startShufflePass()
        applyModes()
        save()
    }

    private fun startShufflePass() {
        val current = engine ?: return
        val headSize = headSizeOf(mutableState.value.entries)
        val inHead = current.itemCount > 0 && current.currentIndex < headSize
        val first = if (inHead) current.currentIndex else -1
        current.shuffleHead(headSize, first)
    }

    private fun headSizeOf(entries: List<QueueEntry>): Int =
        entries.takeWhile { it.part == EntryPart.HEAD }.size

    fun setRepeat(on: Boolean) {
        repeatRule.onUserChanged(on, modesLocked())
        applyModes()
        save()
    }

    private fun applyModes() {
        publishModes()
        val current = engine ?: return
        current.shuffleEnabled = shuffleRule.playerModeFor(modesLocked())
        val waits = repeatWaitsBehindHead()
        current.repeatEnabled = repeatHoldsHead() && !waits
        current.pauseAtEndOfCurrent = waits && current.currentIndex == wrappingHead(current).lastOrNull()
    }

    private fun publishModes() {
        mutableModes.value = PlayModes(shuffleRule.wanted, repeatRule.wanted, modesLocked())
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying) {
            resetFailures()
            stoppedAtEnd = false
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
        val current = engine ?: return
        when (
            ErrorPolicy.decide(
                kind,
                consecutiveTrackFailures,
                current.hasNext(),
                continuationFollows(),
            )
        ) {
            ErrorAction.WaitForNetwork -> waitForNetwork(current)
            ErrorAction.Hold -> emit(QueueEvent.Failed(Stage.PLAYBACK, error))
            ErrorAction.SkipToNext -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, error))
                skipForward(current)
                current.prepare()
                current.play()
            }
            ErrorAction.WaitForMore -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, error))
                val run = jam
                if (run != null) {
                    run.waitingForWave = true
                    ensureJamWave(run)
                } else {
                    waitForMore(current)
                    if (!asking) stoppedAtEnd = true
                }
            }
            ErrorAction.Stop -> {
                emit(QueueEvent.StoppedAfterFailures(consecutiveTrackFailures + 1))
                consecutiveTrackFailures = 0
                if (!current.hasNext()) stoppedAtEnd = true
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
            jam != null || state.isWave -> true
            !state.continueWithVibe || stalled -> false
            else -> asking || waveSessionId != null || history.seeds().isNotEmpty()
        }
    }

    fun loadSource(
        title: String,
        sourceId: String? = null,
        autoplay: Boolean = true,
        loader: suspend () -> List<Track>,
    ) {
        if (jamBlocks()) return
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
                emit(QueueEvent.SourceLoaded(title, mutableState.value.tracks.size))
            }
    }

    fun playWave(seeds: List<String>, title: String, sourceId: String = seeds.first()) {
        if (jamBlocks()) return
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
                sendFeedback(context, WaveEvent.RADIO_STARTED, null, 0.0)
                val entries = batch.tracks.map { QueueEntry(it, EntryPart.CONTINUATION, context) }
                replaceQueue(entries, title, isWave = true, autoplay = true, sourceId = sourceId)
                emit(QueueEvent.WaveStarted(title))
            }
    }

    fun search(text: String, title: String) {
        if (jamBlocks()) return
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
                    emit(QueueEvent.SourceLoaded(name, mutableState.value.tracks.size))
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
        if (jamBlocks()) return
        val current = engine
        playTracker.closeAsSkip()?.let(::onClosed)
        queueGeneration++
        resetFailures()
        forgetContinuation()
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
        applyModes()
        if (current == null) {
            restorePoint = RestorePoint(0, 0, playWhenReady = autoplay)
            save()
            return
        }
        restorePoint = null
        val tracks = playable.map(QueueEntry::track)
        current.setTracks(tracks, playWhenReady = autoplay, headSize = headSizeOf(playable))
        save()
    }

    private fun forgetContinuation() {
        continueAtEnd = false
        asking = false
        stalled = false
        stoppedAtEnd = false
        waveSessionStale = false
        history.clear()
    }

    private fun appendEntries(entries: List<QueueEntry>) {
        if (jam != null) return
        val playable = entries.filter { it.track.available }
        if (playable.isEmpty()) return
        mutableState.update { it.copy(entries = it.entries + playable) }
        engine?.appendTracks(playable.map(QueueEntry::track))
        applyModes()
        save()
    }

    fun clear() {
        if (jamBlocks()) return
        playTracker.closeAsSkip()?.let(::onClosed)
        queueGeneration++
        resetFailures()
        forgetContinuation()
        restorePoint = null
        waveSessionId = null
        waveStationId = ""
        mutableState.value = QueueState(continueWithVibe = continueWithVibeByDefault())
        publishModes()
        save()
        val current = engine ?: return
        current.stop()
        current.clear()
    }

    fun removeIndices(indices: Set<Int>) {
        if (indices.isEmpty() || jamBlocks()) return
        if (engine?.currentIndex in indices) playTracker.closeAsSkip()?.let(::onClosed)
        val sorted = indices.filter { it in mutableState.value.entries.indices }.sortedDescending()
        mutableState.update { it.copy(selected = emptySet()) }
        for (index in sorted) {
            mutableState.update { state ->
                state.copy(entries = state.entries.filterIndexed { i, _ -> i != index })
            }
            engine?.removeAt(index)
        }
        applyModes()
        save()
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
        if (currentTrack()?.id == track.id) dislikeAndSkip(track) else sendDislike(track, QueueEvent.Disliked)
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
                val part = snapshot.parts.getOrNull(i) ?: default
                val batchId = snapshot.batchIds.getOrElse(i) { "" }
                val wave =
                    if (part == EntryPart.CONTINUATION && snapshot.waveSessionId.isNotEmpty()) {
                        WaveContext(snapshot.waveSessionId, snapshot.waveStationId, batchId)
                    } else {
                        null
                    }
                QueueEntry(track, part, wave)
            }
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
        }
        restorePoint = RestorePoint(snapshot.index, snapshot.positionMs, playWhenReady = false)
        if (snapshot.jamSlots != null && snapshot.jamSlots.size == snapshot.tracks.size) {
            mutableState.update { it.copy(jamSlots = snapshot.jamSlots) }
        }
        publishModes()
    }

    private fun startSession(batch: WaveBatch, stationId: String) {
        waveSessionStale = false
        waveSessionId = batch.sessionId
        waveStationId = stationId
        val context = WaveContext(batch.sessionId, stationId, batch.batchId)
        sendFeedback(context, WaveEvent.RADIO_STARTED, null, 0.0)
    }

    private fun save() {
        if (store == null) return
        val state = mutableState.value
        val point = resumePoint()
        val parts = state.entries.map(QueueEntry::part)
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
                jamSlots = state.jamSlots,
                parts = if (parts.all { it == EntryPart.defaultFor(state.isWave) }) emptyList() else parts,
                continueWithVibe = state.continueWithVibe,
                history = history.ids,
            )
        saves.trySend(QueueSnapshotCodec.encode(snapshot))
    }

    private fun maybeLoadMore(current: PlayerEngine) {
        if (jam != null || asking) return
        val state = mutableState.value
        val own = state.isWave
        val session = waveSessionId
        if (own && session == null) return
        if (!own && (!state.continueWithVibe || stalled)) return
        if (remaining(current) > LOAD_MORE_WHEN_LEFT) return
        val seeds = if (session == null || waveSessionStale) newSessionSeeds(own) else emptyList()
        val recent = state.tracks.takeLast(WAVE_HISTORY).map { it.id }
        val request: suspend () -> WaveBatch =
            when {
                seeds.isNotEmpty() -> {
                    { source.startWave(seeds) }
                }
                session != null -> {
                    { source.moreWave(session, recent) }
                }
                else -> return
            }
        val first = !own && session == null
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
                    if (generation == queueGeneration) emit(QueueEvent.Failed(stage, ErrorKind.of(failed)))
                    null
                }
            if (generation != queueGeneration) return@launch
            asking = false
            val resume = continueAtEnd
            continueAtEnd = false
            publishLoadingMore()
            val available = batch?.tracks?.filter { it.available }.orEmpty()
            if (batch == null || available.isEmpty()) {
                if (first) stalled = true
                if (resume) stoppedAtEnd = true
                return@launch
            }
            if (seeds.isNotEmpty()) startSession(batch, seeds.first())
            val sessionId = batch.sessionId.ifEmpty { session.orEmpty() }
            val context = WaveContext(sessionId, waveStationId, batch.batchId)
            val firstNew = mutableState.value.entries.size
            appendEntries(available.map { QueueEntry(it, EntryPart.CONTINUATION, context) })
            val afterAppend = engine ?: return@launch
            if (resume) {
                afterAppend.seekTo(firstNew)
                afterAppend.prepare()
                afterAppend.play()
            }
        }
    }

    private fun newSessionSeeds(own: Boolean): List<String> {
        val station = listOfNotNull(waveStationId.takeIf { waveSessionStale && it.isNotEmpty() })
        return if (own) station else history.seeds().ifEmpty { station }
    }

    private fun remaining(current: PlayerEngine): Int {
        if (repeatHoldsHead()) return Int.MAX_VALUE
        return PlayOrder.remainingAfter(current.playOrder(), current.currentIndex) + 1
    }

    private fun repeatHoldsHead(): Boolean =
        repeatRule.playerModeFor(modesLocked()) && currentEntry()?.part != EntryPart.CONTINUATION

    private fun repeatWaitsBehindHead(): Boolean =
        repeatHoldsHead() && mutableState.value.entries.any { it.part == EntryPart.CONTINUATION }

    private fun publishLoadingMore() {
        mutableState.update { it.copy(loadingMore = asking && (it.isWave || continueAtEnd)) }
    }

    fun startJam(title: String, listener: JamPlaybackListener, waveFeedback: Boolean) {
        if (jam != null) return
        val run = JamRun(listener, waveFeedback)
        newSourceRequest()
        loadJob?.cancel()
        queueGeneration++
        waveSessionId = null
        waveStationId = ""
        waveSessionStale = false
        openWaveContext = null
        continueAtEnd = false
        asking = false
        stalled = false
        stoppedAtEnd = false
        val current = engine
        val state = mutableState.value
        val slots = state.jamSlots
        if (slots?.size == state.tracks.size) {
            val entries = state.entries.zip(slots) { entry, slot -> QueueEntry(entry.track, jamPartOf(slot)) }
            mutableState.update {
                it.copy(entries = entries, title = title, isWave = false, loadingMore = false)
            }
        } else if (current != null && current.itemCount > 0) {
            val kept = state.entries.take(current.currentIndex + 1).map { QueueEntry(it.track) }
            mutableState.value =
                QueueState(
                    entries = kept,
                    title = title,
                    jamSlots = List(kept.size) { JamSlot.Other },
                    continueWithVibe = state.continueWithVibe,
                )
            for (index in current.itemCount - 1 downTo current.currentIndex + 1) current.removeAt(index)
        } else {
            mutableState.value =
                QueueState(title = title, jamSlots = emptyList(), continueWithVibe = state.continueWithVibe)
        }
        jam = run
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
        run.entries = entries
        run.seeds = seeds
        run.seedsVersion = seedsVersion
        applyJamQueue(run)
    }

    fun jamSkip(itemId: String) {
        val current = engine ?: return
        val slot = mutableState.value.jamSlots?.getOrNull(current.currentIndex)
        if (jam != null && slot is JamSlot.Item && slot.itemId == itemId) next()
    }

    fun endJam() {
        val run = jam
        jam = null
        run?.reporter?.cancel()
        publishModes()
        val slots = mutableState.value.jamSlots ?: return
        val current = engine
        if (current != null && current.itemCount > 0) {
            for (index in slots.lastIndex downTo current.currentIndex + 1) {
                if (slots[index] == JamSlot.Wave) jamRemove(current, index)
            }
        }
        mutableState.update { state ->
            state.copy(entries = state.entries.map { QueueEntry(it.track) }, jamSlots = null)
        }
        if (current != null && current.itemCount > 0) {
            current.shuffleHead(current.itemCount, current.currentIndex)
        }
        applyModes()
        save()
    }

    private fun jamBlocks(): Boolean {
        if (jam == null) return false
        emit(QueueEvent.JamActive)
        return true
    }

    private fun applyJamQueue(run: JamRun) {
        val current = engine ?: return
        val slots = mutableState.value.jamSlots ?: return
        if (current.itemCount == 0 || slots.isEmpty()) {
            if (run.entries.isEmpty()) return
            mutableState.update { state ->
                state.copy(
                    entries = run.entries.map { QueueEntry(it.track) },
                    jamSlots = run.entries.map { JamSlot.Item(it.itemId, it.addedBy) },
                )
            }
            current.setTracks(run.entries.map { it.track }, playWhenReady = true)
            save()
            return
        }
        val index = current.currentIndex
        val currentSlot = slots.getOrNull(index)
        val wanted = run.entries.filterNot { currentSlot is JamSlot.Item && it.itemId == currentSlot.itemId }
        val edit = JamTail.edit(slots, index, wanted)
        for (at in edit.removeUntil - 1 downTo edit.removeFrom) jamRemove(current, at)
        val inserted = edit.insert.map { QueueEntry(it.track) to JamSlot.Item(it.itemId, it.addedBy) }
        jamInsert(current, edit.insertAt, inserted)
        if ((current.isEnded || run.waitingForWave) && wanted.isNotEmpty() && index + 1 < current.itemCount) {
            run.waitingForWave = false
            current.seekTo(index + 1)
            current.prepare()
            current.play()
        } else {
            ensureJamWave(run)
        }
        save()
    }

    private fun jamInsert(current: PlayerEngine, index: Int, inserted: List<Pair<QueueEntry, JamSlot>>) {
        if (inserted.isEmpty()) return
        mutableState.update { state ->
            val entries = state.entries.toMutableList().apply { addAll(index, inserted.map { it.first }) }
            val slots = state.jamSlots.orEmpty().toMutableList().apply {
                addAll(index, inserted.map { it.second })
            }
            val selected = state.selected.map { if (it >= index) it + inserted.size else it }.toSet()
            state.copy(entries = entries, jamSlots = slots, selected = selected)
        }
        current.insertTracks(index, inserted.map { it.first.track })
    }

    private fun jamPartOf(slot: JamSlot): EntryPart =
        if (slot == JamSlot.Wave) EntryPart.CONTINUATION else EntryPart.HEAD

    private fun jamRemove(current: PlayerEngine, index: Int) {
        mutableState.update { state ->
            state.copy(
                entries = state.entries.filterIndexed { i, _ -> i != index },
                jamSlots = state.jamSlots?.filterIndexed { i, _ -> i != index },
                selected = state.selected.filter { it != index }.map {
                    if (it >
                        index
                    ) {
                        it - 1
                    } else {
                        it
                    }
                }.toSet(),
            )
        }
        current.removeAt(index)
    }

    private fun ensureJamWave(run: JamRun) {
        val current = engine ?: return
        if (run.waveLoading || jam !== run) return
        val slots = mutableState.value.jamSlots ?: return
        val index = if (current.itemCount == 0) -1 else current.currentIndex
        if (slots.drop(index + 1).any { it is JamSlot.Item }) return
        val previous = run.wave
        if (previous != null &&
            slots.getOrNull(index) != JamSlot.Wave &&
            previous.seedsVersion != run.seedsVersion
        ) {
            for (at in slots.lastIndex downTo index + 1) if (slots[at] == JamSlot.Wave) jamRemove(current, at)
            run.wave = null
        }
        val tail = mutableState.value.jamSlots.orEmpty().drop(index + 1)
        if (tail.count { it == JamSlot.Wave } >= JAM_WAVE_AHEAD) return
        val wave = run.wave
        val seeds =
            run.seeds.ifEmpty {
                listOfNotNull(mutableState.value.tracks.getOrNull(index)?.let { "track:${it.id}" })
            }
        if (wave == null && seeds.isEmpty()) {
            if (current.itemCount == 0 || current.isEnded) reportJam()
            return
        }
        run.waveLoading = true
        val seedsVersion = run.seedsVersion
        val allSlots = mutableState.value.jamSlots.orEmpty()
        val recent =
            mutableState.value.tracks.filterIndexed { i, _ -> allSlots.getOrNull(i) == JamSlot.Wave }
                .takeLast(WAVE_HISTORY)
                .map { it.id }
        scope.launch {
            val batch =
                try {
                    withContext(io) {
                        if (wave == null) source.startWave(seeds) else source.moreWave(wave.sessionId, recent)
                    }
                } catch (failed: Exception) {
                    logFailure(Stage.WAVE, failed)
                    null
                }
            run.waveLoading = false
            if (jam !== run) return@launch
            val playable = batch?.tracks?.filter { it.available }.orEmpty()
            if (batch == null || playable.isEmpty()) return@launch
            val session =
                wave ?: JamWave(batch.sessionId, seedsVersion, seeds.first()).also { started ->
                    run.wave = started
                    if (run.waveFeedback) {
                        sendFeedback(
                            WaveContext(started.sessionId, started.stationId, batch.batchId),
                            WaveEvent.RADIO_STARTED,
                            null,
                            0.0,
                        )
                    }
                }
            val context =
                WaveContext(session.sessionId, session.stationId, batch.batchId).takeIf { run.waveFeedback }
            val engineNow = engine ?: return@launch
            val start = engineNow.itemCount
            jamInsert(
                engineNow,
                start,
                playable.map { QueueEntry(it, EntryPart.CONTINUATION, context) to JamSlot.Wave },
            )
            if (engineNow.isEnded || run.waitingForWave || start == 0) {
                run.waitingForWave = false
                engineNow.seekTo(start)
                engineNow.prepare()
                engineNow.play()
            }
            save()
        }
    }

    /** `onPlayback` for what plays now, as the host needs after `resumed` (HOST-26). */
    fun reportJamPlayback() = reportJam()

    private fun reportJam() {
        val run = jam ?: return
        val current = engine
        val index = current?.currentIndex ?: -1
        val track =
            if (current == null ||
                current.itemCount == 0 ||
                current.isEnded
            ) {
                null
            } else {
                mutableState.value.tracks.getOrNull(index)
            }
        val slot = mutableState.value.jamSlots?.getOrNull(index)
        val next = mutableState.value.tracks.getOrNull(index + 1)
        val positionMs = current?.positionMs?.coerceAtLeast(0) ?: 0
        val playback =
            when {
                track == null -> JamPlayback(JamPlayback.Kind.IDLE, null, null, 0, paused = true)
                slot is JamSlot.Item -> JamPlayback(
                    JamPlayback.Kind.ITEM,
                    slot.itemId,
                    track,
                    positionMs,
                    !playing,
                    next,
                )
                else -> JamPlayback(JamPlayback.Kind.WAVE, null, track, positionMs, !playing, next)
            }
        run.listener.onPlayback(playback)
    }

    companion object {
        const val MY_WAVE_SEED = "user:onyourwave"
        const val LOAD_MORE_WHEN_LEFT = 2
        const val WAVE_HISTORY = 5
        const val RESTART_AFTER_MS = 3_000L
        const val PLAYING_REPORT_MS = 10_000L
        const val JAM_WAVE_AHEAD = 2
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val EVENT_BUFFER = 8
    }
}
