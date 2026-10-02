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
    val tracks: List<Track> = emptyList(),
    val title: String = "",
    val isWave: Boolean = false,
    val loadingMore: Boolean = false,
    val selected: Set<Int> = emptySet(),
    val activeSourceId: String? = null,
    val jamSlots: List<JamSlot>? = null,
)

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
    private val waveContexts = HashMap<String, WaveContext>()
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
    private val saves = Channel<String>(Channel.CONFLATED)
    private var waveSessionStale = false
    private var playing = false

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
        engine.shuffleEnabled = shuffleRule.playerModeFor(state.isWave)
        engine.repeatEnabled = repeatRule.playerModeFor(state.isWave)
        val index = restore.index.coerceIn(0, state.tracks.lastIndex)
        engine.setTracks(state.tracks, restore.playWhenReady, index, restore.positionMs)
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
        when {
            current.hasNext() -> current.skipToNext()
            mutableState.value.isWave -> {
                emit(QueueEvent.LoadingMore)
                requestMore()
            }
            else -> stop()
        }
    }

    fun previous() {
        val current = engine ?: return
        if (jam != null) {
            restart(current)
            return
        }
        when {
            current.positionMs > RESTART_AFTER_MS -> restart(current)
            current.hasPrevious() -> current.skipToPrevious()
            else -> restart(current)
        }
    }

    fun stop() {
        val current = engine ?: return
        current.pause()
        restart(current)
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
        val context = waveContexts[track.id]
        openWaveContext = context
        if (context != null) sendFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
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

    private fun registerBatch(sessionId: String, batch: WaveBatch) {
        val context = WaveContext(sessionId, waveStationId, batch.batchId)
        for (track in batch.tracks) if (track.available) waveContexts[track.id] = context
    }

    fun onItemChanged(track: Track?, transition: Transition, isPlaying: Boolean) {
        val current = engine ?: return
        playing = isPlaying
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
        maybeLoadMore(current)
        jam?.let { run ->
            run.waitingForWave = true
            reportJam()
            ensureJamWave(run)
        }
    }

    private fun modesLocked(): Boolean = mutableState.value.isWave || jam != null

    fun onShuffleChanged(enabled: Boolean) {
        val current = engine ?: return
        val target = shuffleRule.onPlayerChanged(enabled, modesLocked())
        if (target != enabled) current.shuffleEnabled = target
    }

    fun onRepeatChanged(enabled: Boolean) {
        val current = engine ?: return
        val target = repeatRule.onPlayerChanged(enabled, modesLocked())
        if (target != enabled) current.repeatEnabled = target
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying) resetFailures()
        playTracker.onPlayingChanged(isPlaying)?.let(::onStarted)
        if (!isPlaying) save()
        reportJam()
    }

    fun onFailure(kind: FailureKind, error: ErrorKind) {
        val current = engine ?: return
        when (
            ErrorPolicy.decide(
                kind,
                consecutiveTrackFailures,
                current.hasNext(),
                mutableState.value.isWave || jam != null,
            )
        ) {
            ErrorAction.WaitForNetwork -> waitForNetwork(current)
            ErrorAction.Hold -> emit(QueueEvent.Failed(Stage.PLAYBACK, error))
            ErrorAction.SkipToNext -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, error))
                current.skipToNext()
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
                    continueAtEnd = true
                    maybeLoadMore(current)
                }
            }
            ErrorAction.Stop -> {
                emit(QueueEvent.StoppedAfterFailures(consecutiveTrackFailures + 1))
                consecutiveTrackFailures = 0
            }
        }
    }

    fun requestMore() {
        val current = engine ?: return
        if (!mutableState.value.isWave) return
        continueAtEnd = true
        maybeLoadMore(current)
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
                waveContexts.clear()
                registerBatch(batch.sessionId, batch)
                sendFeedback(
                    WaveContext(batch.sessionId, waveStationId, batch.batchId),
                    WaveEvent.RADIO_STARTED,
                    null,
                    0.0,
                )
                setQueue(batch.tracks, title, isWave = true, autoplay = true, sourceId = sourceId)
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
        if (jamBlocks()) return
        val current = engine
        playTracker.closeAsSkip()?.let(::onClosed)
        queueGeneration++
        resetFailures()
        continueAtEnd = false
        waveSessionStale = false
        val playable = tracks.filter { it.available }
        if (!isWave) {
            waveSessionId = null
            waveContexts.clear()
        }
        mutableState.value =
            QueueState(tracks = playable, title = title, isWave = isWave, activeSourceId = sourceId)
        if (current == null) {
            restorePoint = RestorePoint(0, 0, playWhenReady = autoplay)
            save()
            return
        }
        restorePoint = null
        current.shuffleEnabled = shuffleRule.playerModeFor(isWave)
        current.repeatEnabled = repeatRule.playerModeFor(isWave)
        current.setTracks(playable, playWhenReady = autoplay)
        save()
    }

    fun appendTracks(tracks: List<Track>) {
        if (jamBlocks()) return
        val playable = tracks.filter { it.available }
        if (playable.isEmpty()) return
        mutableState.update { it.copy(tracks = it.tracks + playable) }
        engine?.appendTracks(playable)
        save()
    }

    fun clear() {
        if (jamBlocks()) return
        playTracker.closeAsSkip()?.let(::onClosed)
        queueGeneration++
        resetFailures()
        continueAtEnd = false
        restorePoint = null
        waveSessionId = null
        waveSessionStale = false
        mutableState.value = QueueState()
        save()
        val current = engine ?: return
        current.stop()
        current.clear()
    }

    fun removeIndices(indices: Set<Int>) {
        if (indices.isEmpty() || jamBlocks()) return
        if (engine?.currentIndex in indices) playTracker.closeAsSkip()?.let(::onClosed)
        val sorted = indices.filter { it in mutableState.value.tracks.indices }.sortedDescending()
        engine?.let { current -> for (index in sorted) current.removeAt(index) }
        mutableState.update { state ->
            state.copy(
                tracks = state.tracks.filterIndexed { index, _ ->
                    index !in indices
                },
                selected = emptySet(),
            )
        }
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
        val liked = source.isLiked(track.id)
        scope.launch {
            try {
                withContext(io) { source.setLiked(track.id, !liked) }
                emit(QueueEvent.LikeChanged(liked = !liked))
            } catch (failed: Exception) {
                fail(Stage.LIKE, failed)
            }
        }
    }

    fun dislikeAndSkip(track: Track) {
        scope.launch {
            try {
                withContext(io) { source.dislike(track.id) }
                emit(QueueEvent.DislikedAndSkipped)
            } catch (failed: Exception) {
                fail(Stage.LIKE, failed)
            }
        }
        next()
    }

    fun currentWaveSeed(): String? =
        waveStationId.takeIf { mutableState.value.isWave && waveSessionId != null && it.isNotEmpty() }

    fun likeCurrent() {
        currentTrack()?.let(::toggleLike)
    }

    fun dislikeCurrent() {
        currentTrack()?.let(::dislikeAndSkip)
    }

    private fun currentTrack(): Track? {
        val current = engine ?: return null
        if (current.itemCount == 0) return null
        return mutableState.value.tracks.getOrNull(current.currentIndex)
    }

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
        mutableState.value =
            QueueState(
                tracks = snapshot.tracks,
                title = snapshot.title,
                isWave = snapshot.isWave,
                activeSourceId = snapshot.sourceId,
            )
        shuffleRule.onPlayerChanged(snapshot.shuffle, isWave = false)
        repeatRule.onPlayerChanged(snapshot.repeat, isWave = false)
        if (snapshot.isWave && snapshot.waveSessionId.isNotEmpty()) {
            waveSessionId = snapshot.waveSessionId
            waveStationId = snapshot.waveStationId
            waveSessionStale = true
            snapshot.tracks.forEachIndexed { i, track ->
                val batchId = snapshot.batchIds.getOrElse(i) { "" }
                waveContexts[track.id] = WaveContext(snapshot.waveSessionId, snapshot.waveStationId, batchId)
            }
        }
        restorePoint = RestorePoint(snapshot.index, snapshot.positionMs, playWhenReady = false)
        if (snapshot.jamSlots != null && snapshot.jamSlots.size == snapshot.tracks.size) {
            mutableState.update { it.copy(jamSlots = snapshot.jamSlots) }
        }
    }

    /** A wave session does not outlive the process: the restored wave asks its station for a new one. */
    private fun startRestoredSession(batch: WaveBatch) {
        waveSessionStale = false
        waveSessionId = batch.sessionId
        sendFeedback(
            WaveContext(batch.sessionId, waveStationId, batch.batchId),
            WaveEvent.RADIO_STARTED,
            null,
            0.0,
        )
    }

    private fun save() {
        if (store == null) return
        val state = mutableState.value
        val point = resumePoint()
        val snapshot =
            QueueSnapshot(
                tracks = state.tracks,
                batchIds = state.tracks.map { waveContexts[it.id]?.batchId.orEmpty() },
                title = state.title,
                sourceId = state.activeSourceId,
                isWave = state.isWave,
                waveSessionId = if (state.isWave) waveSessionId.orEmpty() else "",
                waveStationId = if (state.isWave) waveStationId else "",
                index = point?.index ?: 0,
                positionMs = point?.positionMs ?: 0,
                shuffle = shuffleRule.wanted,
                repeat = repeatRule.wanted,
                jamSlots = state.jamSlots,
            )
        saves.trySend(QueueSnapshotCodec.encode(snapshot))
    }

    private fun maybeLoadMore(current: PlayerEngine) {
        val state = mutableState.value
        val session = waveSessionId ?: return
        if (!state.isWave || state.loadingMore) return
        val left = PlayOrder.remainingAfter(current.playOrder(), current.currentIndex) + 1
        if (left > LOAD_MORE_WHEN_LEFT) return
        val generation = queueGeneration
        val wasEnded = current.isEnded
        val oldCount = current.itemCount
        mutableState.update { it.copy(loadingMore = true) }
        val recent = state.tracks.takeLast(WAVE_HISTORY).map { it.id }
        scope.launch {
            val batch: WaveBatch? =
                try {
                    if (waveSessionStale && waveStationId.isNotEmpty()) {
                        withContext(io) { source.startWave(listOf(waveStationId)) }
                    } else {
                        withContext(io) { source.moreWave(session, recent) }
                    }
                } catch (failed: Exception) {
                    logFailure(Stage.WAVE_MORE, failed)
                    if (generation ==
                        queueGeneration
                    ) {
                        emit(QueueEvent.Failed(Stage.WAVE_MORE, ErrorKind.of(failed)))
                    }
                    null
                }
            if (generation != queueGeneration) return@launch
            mutableState.update { it.copy(loadingMore = false) }
            val resume = continueAtEnd
            continueAtEnd = false
            if (batch == null || batch.tracks.none { it.available }) return@launch
            if (waveSessionStale) startRestoredSession(batch)
            registerBatch(batch.sessionId.ifEmpty { session }, batch)
            appendTracks(batch.tracks)
            val afterAppend = engine ?: return@launch
            if (wasEnded || afterAppend.isEnded || resume) {
                afterAppend.seekTo(oldCount)
                afterAppend.prepare()
                afterAppend.play()
            }
        }
    }

    fun startJam(title: String, listener: JamPlaybackListener, waveFeedback: Boolean) {
        if (jam != null) return
        val run = JamRun(listener, waveFeedback)
        newSourceRequest()
        loadJob?.cancel()
        waveSessionId = null
        waveSessionStale = false
        waveContexts.clear()
        openWaveContext = null
        continueAtEnd = false
        val current = engine
        val state = mutableState.value
        if (state.jamSlots?.size == state.tracks.size) {
            mutableState.update { it.copy(title = title, isWave = false) }
        } else if (current != null && current.itemCount > 0) {
            val kept = state.tracks.take(current.currentIndex + 1)
            mutableState.value =
                QueueState(tracks = kept, title = title, jamSlots = List(kept.size) { JamSlot.Other })
            for (index in current.itemCount - 1 downTo current.currentIndex + 1) current.removeAt(index)
        } else {
            mutableState.value = QueueState(title = title, jamSlots = emptyList())
        }
        jam = run
        current?.shuffleEnabled = false
        current?.repeatEnabled = false
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
        val slots = mutableState.value.jamSlots ?: return
        val current = engine
        if (current != null && current.itemCount > 0) {
            for (index in slots.lastIndex downTo current.currentIndex + 1) {
                if (slots[index] == JamSlot.Wave) jamRemove(current, index)
            }
        }
        waveContexts.clear()
        mutableState.update { it.copy(jamSlots = null) }
        current?.shuffleEnabled = shuffleRule.playerModeFor(isWave = false)
        current?.repeatEnabled = repeatRule.playerModeFor(isWave = false)
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
                    tracks = run.entries.map { it.track },
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
        jamInsert(current, edit.insertAt, edit.insert.map { it.track to JamSlot.Item(it.itemId, it.addedBy) })
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

    private fun jamInsert(current: PlayerEngine, index: Int, entries: List<Pair<Track, JamSlot>>) {
        if (entries.isEmpty()) return
        mutableState.update { state ->
            val tracks = state.tracks.toMutableList().apply { addAll(index, entries.map { it.first }) }
            val slots = state.jamSlots.orEmpty().toMutableList().apply {
                addAll(index, entries.map { it.second })
            }
            val selected = state.selected.map { if (it >= index) it + entries.size else it }.toSet()
            state.copy(tracks = tracks, jamSlots = slots, selected = selected)
        }
        current.insertTracks(index, entries.map { it.first })
    }

    private fun jamRemove(current: PlayerEngine, index: Int) {
        mutableState.update { state ->
            state.copy(
                tracks = state.tracks.filterIndexed { i, _ -> i != index },
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
            if (run.waveFeedback) {
                val context = WaveContext(session.sessionId, session.stationId, batch.batchId)
                for (track in playable) waveContexts[track.id] = context
            }
            val engineNow = engine ?: return@launch
            val start = engineNow.itemCount
            jamInsert(engineNow, start, playable.map { it to JamSlot.Wave })
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
