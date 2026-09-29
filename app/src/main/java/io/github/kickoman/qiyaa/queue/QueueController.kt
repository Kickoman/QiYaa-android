package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.WaveContext
import io.github.kickoman.qiyaa.yandex.WaveEvent
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
        reportPlay(track)
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
        apply(playTracker.onItemChanged(track, transition, isPlaying))
        save()
        maybeLoadMore(current)
    }

    fun onEnded() {
        apply(playTracker.onEnded())
        val current = engine ?: return
        maybeLoadMore(current)
    }

    fun onShuffleChanged(enabled: Boolean) {
        val current = engine ?: return
        val target = shuffleRule.onPlayerChanged(enabled, mutableState.value.isWave)
        if (target != enabled) current.shuffleEnabled = target
    }

    fun onRepeatChanged(enabled: Boolean) {
        val current = engine ?: return
        val target = repeatRule.onPlayerChanged(enabled, mutableState.value.isWave)
        if (target != enabled) current.repeatEnabled = target
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) resetFailures()
        playTracker.onPlayingChanged(isPlaying)?.let(::onStarted)
        if (!isPlaying) save()
    }

    fun onFailure(kind: FailureKind, message: String) {
        val current = engine ?: return
        when (
            ErrorPolicy.decide(
                kind,
                consecutiveTrackFailures,
                current.hasNext(),
                mutableState.value.isWave,
            )
        ) {
            ErrorAction.WaitForNetwork -> waitForNetwork(current)
            ErrorAction.Hold -> emit(QueueEvent.Failed(Stage.PLAYBACK, message))
            ErrorAction.SkipToNext -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, message))
                current.skipToNext()
                current.prepare()
                current.play()
            }
            ErrorAction.WaitForMore -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, message))
                continueAtEnd = true
                maybeLoadMore(current)
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
        val ticket = newSourceRequest()
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val tracks =
                    try {
                        withContext(io) { loader() }
                    } catch (failed: Exception) {
                        if (isLatest(ticket)) emit(QueueEvent.Failed(Stage.SOURCE, describe(failed)))
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
        val ticket = newSourceRequest()
        loadJob?.cancel()
        emit(QueueEvent.SourceLoading(title))
        loadJob =
            scope.launch {
                val batch =
                    try {
                        withContext(io) { source.startWave(seeds) }
                    } catch (failed: Exception) {
                        if (isLatest(ticket)) emit(QueueEvent.Failed(Stage.WAVE, describe(failed)))
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
                    if (isLatest(ticket)) emit(QueueEvent.Failed(Stage.SEARCH, describe(failed)))
                }
            }
    }

    fun setQueue(tracks: List<Track>, title: String, isWave: Boolean, autoplay: Boolean, sourceId: String?) {
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
        val playable = tracks.filter { it.available }
        if (playable.isEmpty()) return
        mutableState.update { it.copy(tracks = it.tracks + playable) }
        engine?.appendTracks(playable)
        save()
    }

    fun clear() {
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
        if (indices.isEmpty()) return
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
                emit(QueueEvent.Failed(Stage.LIKE, describe(failed)))
            }
        }
    }

    fun dislikeAndSkip(track: Track) {
        scope.launch {
            try {
                withContext(io) { source.dislike(track.id) }
                emit(QueueEvent.DislikedAndSkipped)
            } catch (failed: Exception) {
                emit(QueueEvent.Failed(Stage.LIKE, describe(failed)))
            }
        }
        next()
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

    private fun describe(failed: Exception): String = failed.message ?: failed.javaClass.simpleName

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
                    if (generation ==
                        queueGeneration
                    ) {
                        emit(QueueEvent.Failed(Stage.WAVE_MORE, describe(failed)))
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

    companion object {
        const val MY_WAVE_SEED = "user:onyourwave"
        const val LOAD_MORE_WHEN_LEFT = 2
        const val WAVE_HISTORY = 5
        const val RESTART_AFTER_MS = 3_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val EVENT_BUFFER = 8
    }
}
