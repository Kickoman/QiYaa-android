package io.github.kickoman.qiyaa.queue

import io.github.kickoman.qiyaa.queue.QueueEvent.Stage
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

class QueueController(
    private val source: MusicSource,
    private val connectivity: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val newPlayId: () -> String = { UUID.randomUUID().toString() },
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
    private var reportedItemId: String? = null
    private var consecutiveTrackFailures = 0
    private var networkRetryAttempt = 0
    private var retryJob: Job? = null
    private val shuffleRule = WaveModeRule()
    private val repeatRule = WaveModeRule()

    fun attach(engine: PlayerEngine) {
        this.engine = engine
    }

    fun detach(engine: PlayerEngine) {
        if (this.engine === engine) this.engine = null
    }

    fun onItemChanged(track: Track?) {
        val current = engine ?: return
        if (track != null && reportedItemId != track.id) {
            reportedItemId = track.id
            reportPlay(track)
        }
        maybeLoadMore(current)
    }

    fun onEnded() {
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
    }

    fun onFailure(kind: FailureKind, message: String) {
        val current = engine ?: return
        when (ErrorPolicy.decide(kind, consecutiveTrackFailures, current.hasNext())) {
            ErrorAction.WaitForNetwork -> waitForNetwork(current)
            ErrorAction.Hold -> emit(QueueEvent.Failed(Stage.PLAYBACK, message))
            ErrorAction.SkipToNext -> {
                consecutiveTrackFailures++
                emit(QueueEvent.Failed(Stage.PLAYBACK, message))
                current.skipToNext()
                current.prepare()
                current.play()
            }
            ErrorAction.Stop -> {
                emit(QueueEvent.StoppedAfterFailures(consecutiveTrackFailures + 1))
                consecutiveTrackFailures = 0
            }
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
        if (current == null) {
            emit(QueueEvent.PlayerNotReady)
            return
        }
        queueGeneration++
        resetFailures()
        val playable = tracks.filter { it.available }
        if (!isWave) waveSessionId = null
        mutableState.value =
            QueueState(tracks = playable, title = title, isWave = isWave, activeSourceId = sourceId)
        reportedItemId = null
        current.shuffleEnabled = shuffleRule.playerModeFor(isWave)
        current.repeatEnabled = repeatRule.playerModeFor(isWave)
        current.setTracks(playable, playWhenReady = autoplay)
    }

    fun appendTracks(tracks: List<Track>) {
        val current = engine ?: return
        val playable = tracks.filter { it.available }
        if (playable.isEmpty()) return
        mutableState.update { it.copy(tracks = it.tracks + playable) }
        current.appendTracks(playable)
    }

    fun clear() {
        val current = engine ?: return
        queueGeneration++
        resetFailures()
        waveSessionId = null
        mutableState.value = QueueState()
        current.stop()
        current.clear()
    }

    fun removeIndices(indices: Set<Int>) {
        val current = engine ?: return
        if (indices.isEmpty()) return
        val sorted = indices.filter { it in mutableState.value.tracks.indices }.sortedDescending()
        for (index in sorted) current.removeAt(index)
        mutableState.update { state ->
            state.copy(
                tracks = state.tracks.filterIndexed { index, _ ->
                    index !in indices
                },
                selected = emptySet(),
            )
        }
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

    fun syncFromPlayer() {
        val current = engine ?: return
        val count = current.itemCount
        if (count == mutableState.value.tracks.size) return
        val tracks = (0 until count).map(current::trackAt)
        mutableState.update { it.copy(tracks = tracks, selected = emptySet()) }
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
        val current = engine ?: return
        if (current.hasNext()) {
            current.skipToNext()
        } else if (!mutableState.value.isWave) {
            current.stop()
        }
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
                    withContext(io) { source.moreWave(session, recent) }
                } catch (failed: Exception) {
                    emit(QueueEvent.Failed(Stage.WAVE_MORE, describe(failed)))
                    null
                }
            if (generation != queueGeneration) return@launch
            mutableState.update { it.copy(loadingMore = false) }
            if (batch == null || batch.tracks.isEmpty()) return@launch
            appendTracks(batch.tracks)
            val afterAppend = engine ?: return@launch
            if (wasEnded || afterAppend.isEnded) {
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
        private const val EVENT_BUFFER = 8
    }
}
