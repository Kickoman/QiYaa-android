package io.github.kickoman.qiyaa.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.kickoman.qiyaa.playback.QueueEvent.Stage
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.YandexApi
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class QueueManager(
    private val library: Library,
    private val api: YandexApi,
    private val connectivity: Flow<Boolean>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutableState = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<QueueEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<QueueEvent> = mutableEvents.asSharedFlow()

    @Volatile
    var player: Player? = null
        private set

    private var waveSessionId: String? = null
    private var queueGeneration = 0L
    private var sourceTicket = 0L
    private var loadJob: Job? = null
    private var reportedItemId: String? = null
    private var consecutiveTrackFailures = 0
    private var networkRetryAttempt = 0
    private var retryJob: Job? = null
    private val shuffleRule = ShuffleRule()

    private val listener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val current = player ?: return
                if (mediaItem != null && reportedItemId != mediaItem.mediaId) {
                    reportedItemId = mediaItem.mediaId
                    reportPlay(MediaItems.toTrack(mediaItem))
                }
                maybeLoadMore(current)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val current = player ?: return
                if (playbackState == Player.STATE_ENDED) maybeLoadMore(current)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                val current = player ?: return
                val target = shuffleRule.onPlayerChanged(shuffleModeEnabled, mutableState.value.isWave)
                if (target != shuffleModeEnabled) current.shuffleModeEnabled = target
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) resetFailures()
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = player ?: return
                val kind = PlaybackFailures.classify(error.errorCode, error.cause)
                val message = error.cause?.message ?: error.errorCodeName
                when (ErrorPolicy.decide(kind, consecutiveTrackFailures, current.hasNextMediaItem())) {
                    ErrorAction.WaitForNetwork -> waitForNetwork(current)
                    ErrorAction.Hold -> emit(QueueEvent.Failed(Stage.PLAYBACK, message))
                    ErrorAction.SkipToNext -> {
                        consecutiveTrackFailures++
                        emit(QueueEvent.Failed(Stage.PLAYBACK, message))
                        current.seekToNextMediaItem()
                        current.prepare()
                        current.play()
                    }
                    ErrorAction.Stop -> {
                        emit(QueueEvent.StoppedAfterFailures(consecutiveTrackFailures + 1))
                        consecutiveTrackFailures = 0
                    }
                }
            }
        }

    fun attach(player: Player) {
        this.player = player
        player.addListener(listener)
    }

    fun detach(player: Player) {
        player.removeListener(listener)
        if (this.player === player) this.player = null
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
                        withContext(Dispatchers.IO) { loader() }
                    } catch (failed: Exception) {
                        if (isLatest(ticket)) emit(QueueEvent.Failed(Stage.SOURCE, describe(failed)))
                        return@launch
                    }
                if (!isLatest(ticket)) return@launch
                if (tracks.isEmpty()) {
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
                        withContext(Dispatchers.IO) { library.startWave(seeds) }
                    } catch (failed: Exception) {
                        if (isLatest(ticket)) emit(QueueEvent.Failed(Stage.WAVE, describe(failed)))
                        return@launch
                    }
                if (!isLatest(ticket)) return@launch
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
                    val result = withContext(Dispatchers.IO) { library.search(text) }
                    if (!isLatest(ticket)) return@launch
                    val (tracks, name) =
                        withContext(Dispatchers.IO) {
                            when {
                                result.bestType == "artist" && result.bestId.isNotEmpty() ->
                                    library.artistTopTracks(result.bestId) to result.bestName
                                result.bestType == "album" && result.bestId.isNotEmpty() ->
                                    library.albumTracks(result.bestId) to result.bestName
                                else -> result.tracks to title
                            }
                        }
                    if (!isLatest(ticket)) return@launch
                    if (tracks.isEmpty()) {
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
        val current = player
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
        current.shuffleModeEnabled = shuffleRule.playerModeFor(isWave)
        current.setMediaItems(playable.map(MediaItems::toMediaItem), 0, 0L)
        current.prepare()
        current.playWhenReady = autoplay
    }

    fun appendTracks(tracks: List<Track>) {
        val current = player ?: return
        val playable = tracks.filter { it.available }
        if (playable.isEmpty()) return
        mutableState.update { it.copy(tracks = it.tracks + playable) }
        current.addMediaItems(playable.map(MediaItems::toMediaItem))
    }

    fun clear() {
        val current = player ?: return
        queueGeneration++
        resetFailures()
        waveSessionId = null
        mutableState.value = QueueState()
        current.stop()
        current.clearMediaItems()
    }

    fun removeIndices(indices: Set<Int>) {
        val current = player ?: return
        if (indices.isEmpty()) return
        val sorted = indices.filter { it in mutableState.value.tracks.indices }.sortedDescending()
        for (index in sorted) current.removeMediaItem(index)
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
        val current = player ?: return
        val count = current.mediaItemCount
        if (count == mutableState.value.tracks.size) return
        val tracks = (0 until count).map { MediaItems.toTrack(current.getMediaItemAt(it)) }
        mutableState.update { it.copy(tracks = tracks, selected = emptySet()) }
    }

    fun toggleLike(track: Track) {
        val liked = library.isLiked(track.id)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { library.setLiked(track.id, !liked) }
                emit(QueueEvent.LikeChanged(liked = !liked))
            } catch (failed: Exception) {
                emit(QueueEvent.Failed(Stage.LIKE, describe(failed)))
            }
        }
    }

    fun dislikeAndSkip(track: Track) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { library.dislike(track.id) }
                emit(QueueEvent.DislikedAndSkipped)
            } catch (failed: Exception) {
                emit(QueueEvent.Failed(Stage.LIKE, describe(failed)))
            }
        }
        val current = player ?: return
        if (current.hasNextMediaItem()) {
            current.seekToNextMediaItem()
        } else if (!mutableState.value.isWave) {
            current.stop()
        }
    }

    private fun waitForNetwork(current: Player) {
        if (retryJob?.isActive == true) return
        if (networkRetryAttempt == 0) emit(QueueEvent.WaitingForNetwork)
        val attempt = networkRetryAttempt++
        retryJob =
            scope.launch {
                ErrorPolicy.awaitRetry(connectivity, attempt)
                if (player === current) current.prepare()
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
        val account = library.account.value
        if (!account.isValid) return
        scope.launch(Dispatchers.IO) {
            try {
                api.reportPlayStarted(account, track, UUID.randomUUID().toString())
            } catch (ignored: Exception) {
                // Listen marks are best-effort; the desktop app drops them the same way.
            }
        }
    }

    private fun maybeLoadMore(current: Player) {
        val state = mutableState.value
        val session = waveSessionId ?: return
        if (!state.isWave || state.loadingMore) return
        val order = PlayOrder.of(current.currentTimeline, current.shuffleModeEnabled)
        val left = PlayOrder.remainingAfter(order, current.currentMediaItemIndex) + 1
        if (left > LOAD_MORE_WHEN_LEFT) return
        val generation = queueGeneration
        val wasEnded = current.playbackState == Player.STATE_ENDED
        val oldCount = current.mediaItemCount
        mutableState.update { it.copy(loadingMore = true) }
        val recent = state.tracks.takeLast(WAVE_HISTORY).map { it.id }
        scope.launch {
            val batch: WaveBatch? =
                try {
                    withContext(Dispatchers.IO) { library.moreWave(session, recent) }
                } catch (failed: Exception) {
                    emit(QueueEvent.Failed(Stage.WAVE_MORE, describe(failed)))
                    null
                }
            if (generation != queueGeneration) return@launch
            mutableState.update { it.copy(loadingMore = false) }
            if (batch == null || batch.tracks.isEmpty()) return@launch
            appendTracks(batch.tracks)
            val afterAppend = player ?: return@launch
            if (wasEnded || afterAppend.playbackState == Player.STATE_ENDED) {
                afterAppend.seekTo(oldCount, 0L)
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
