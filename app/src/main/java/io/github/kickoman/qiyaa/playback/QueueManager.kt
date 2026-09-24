package io.github.kickoman.qiyaa.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import io.github.kickoman.qiyaa.yandex.YandexApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What the UI needs to know about the queue beyond what ExoPlayer exposes. */
data class QueueState(
    val tracks: List<Track> = emptyList(),
    val title: String = "",
    val isWave: Boolean = false,
    val loadingMore: Boolean = false,
    val selected: Set<Int> = emptySet(),
    /** Station or source id currently playing, for chip highlighting. */
    val activeSourceId: String? = null,
)

/**
 * Port of the queue half of src/core/Player.cpp on top of ExoPlayer: sources (finite lists or
 * an endless wave), refill when ≤ 2 tracks are left, play-audio reporting, dislike = skip.
 * The player is owned by [PlaybackService] and attached here while the service lives.
 */
class QueueManager(private val library: Library, private val api: YandexApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Status toasts ("My Wave: wave started", errors). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    @Volatile
    var player: Player? = null
        private set

    private var waveSessionId: String? = null
    private var queueGeneration = 0L
    private var sourceTicket = 0L
    private var loadJob: Job? = null
    private var reportedItemId: String? = null

    fun say(msg: String) {
        _messages.tryEmit(msg)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            if (mediaItem != null && reportedItemId != mediaItem.mediaId) {
                reportedItemId = mediaItem.mediaId
                reportPlay(MediaItems.toTrack(mediaItem))
            }
            maybeLoadMore(p)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            if (playbackState == Player.STATE_ENDED) maybeLoadMore(p)
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause
            val msg = cause?.message ?: error.errorCodeName
            say("Playback error: $msg")
            val p = player ?: return
            // Skip a broken track (same as the desktop app: failed stream → next).
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
        }
    }

    // ------------------------------------------------------------------ service wiring

    fun attach(player: Player) {
        this.player = player
        player.addListener(listener)
    }

    fun detach(player: Player) {
        player.removeListener(listener)
        if (this.player === player) this.player = null
    }

    // ------------------------------------------------------------------ sources

    /** Takes a ticket before an async load; only the latest ticket may apply its result. */
    private fun newSourceRequest(): Long = ++sourceTicket

    private fun isLatest(ticket: Long) = ticket == sourceTicket

    /** Loads a finite source; the loader runs on IO and only the newest request wins. */
    fun loadSource(title: String, sourceId: String? = null, autoplay: Boolean = true, loader: suspend () -> List<Track>) {
        val ticket = newSourceRequest()
        loadJob?.cancel()
        loadJob = scope.launch {
            val tracks = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { loader() }
            } catch (e: Exception) {
                if (isLatest(ticket)) say("Error: ${e.message}")
                return@launch
            }
            if (!isLatest(ticket)) return@launch
            if (tracks.isEmpty()) {
                say("$title: empty")
                return@launch
            }
            setQueue(tracks, title, isWave = false, autoplay = autoplay, sourceId = sourceId)
            say("$title: ${_state.value.tracks.size} tracks")
        }
    }

    /** Starts an endless rotor wave from [seeds] (e.g. `user:onyourwave` or a station id). */
    fun playWave(seeds: List<String>, title: String, sourceId: String = seeds.first()) {
        val ticket = newSourceRequest()
        loadJob?.cancel()
        say("$title: loading…")
        loadJob = scope.launch {
            val batch = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { library.startWave(seeds) }
            } catch (e: Exception) {
                if (isLatest(ticket)) say("Wave error: ${e.message}")
                return@launch
            }
            if (!isLatest(ticket)) return@launch
            waveSessionId = batch.sessionId
            setQueue(batch.tracks, title, isWave = true, autoplay = true, sourceId = sourceId)
            say("$title: wave started")
        }
    }

    fun playMyWave() = playWave(listOf("user:onyourwave"), "My Wave")

    fun search(text: String) {
        val title = "Search: $text"
        val ticket = newSourceRequest()
        loadJob?.cancel()
        say("$title…")
        loadJob = scope.launch {
            try {
                val r = kotlinx.coroutines.withContext(Dispatchers.IO) { library.search(text) }
                if (!isLatest(ticket)) return@launch
                val (tracks, name) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    when {
                        r.bestType == "artist" && r.bestId.isNotEmpty() -> library.artistTopTracks(r.bestId) to r.bestName
                        r.bestType == "album" && r.bestId.isNotEmpty() -> library.albumTracks(r.bestId) to r.bestName
                        else -> r.tracks to title
                    }
                }
                if (!isLatest(ticket)) return@launch
                if (tracks.isEmpty()) {
                    say("Nothing found")
                    return@launch
                }
                setQueue(tracks, name, isWave = false, autoplay = true, sourceId = null)
                say("$name: ${_state.value.tracks.size} tracks")
            } catch (e: Exception) {
                if (isLatest(ticket)) say("Search error: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ queue edits

    fun setQueue(tracks: List<Track>, title: String, isWave: Boolean, autoplay: Boolean, sourceId: String?) {
        val p = player
        if (p == null) {
            say("Player is still starting, try again")
            return
        }
        queueGeneration++
        val playable = tracks.filter { it.available }
        if (!isWave) waveSessionId = null
        _state.value = QueueState(tracks = playable, title = title, isWave = isWave, activeSourceId = sourceId)
        reportedItemId = null
        p.setMediaItems(playable.map(MediaItems::toMediaItem), 0, 0L)
        p.prepare()
        p.playWhenReady = autoplay
    }

    fun appendTracks(tracks: List<Track>) {
        val p = player ?: return
        val playable = tracks.filter { it.available }
        if (playable.isEmpty()) return
        _state.update { it.copy(tracks = it.tracks + playable) }
        p.addMediaItems(playable.map(MediaItems::toMediaItem))
    }

    fun clear() {
        val p = player ?: return
        queueGeneration++
        waveSessionId = null
        _state.value = QueueState()
        p.stop()
        p.clearMediaItems()
    }

    fun removeIndices(indices: Set<Int>) {
        val p = player ?: return
        if (indices.isEmpty()) return
        val sorted = indices.filter { it in _state.value.tracks.indices }.sortedDescending()
        for (i in sorted) p.removeMediaItem(i)
        _state.update { s -> s.copy(tracks = s.tracks.filterIndexed { i, _ -> i !in indices }, selected = emptySet()) }
    }

    fun toggleSelected(index: Int) = _state.update { s ->
        s.copy(selected = if (index in s.selected) s.selected - index else s.selected + index)
    }

    fun selectAllOrNone() = _state.update { s ->
        s.copy(selected = if (s.selected.size == s.tracks.size) emptySet() else s.tracks.indices.toSet())
    }

    /** Keeps [state.tracks] in sync when ExoPlayer's timeline changed through the session (e.g. notification). */
    fun syncFromPlayer() {
        val p = player ?: return
        val n = p.mediaItemCount
        if (n == _state.value.tracks.size) return
        val tracks = (0 until n).map { MediaItems.toTrack(p.getMediaItemAt(it)) }
        _state.update { it.copy(tracks = tracks, selected = emptySet()) }
    }

    // ------------------------------------------------------------------ likes

    fun toggleLike(track: Track) {
        val liked = library.isLiked(track.id)
        scope.launch {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { library.setLiked(track.id, !liked) }
                say(if (liked) "Removed from Liked" else "Added to Liked")
            } catch (e: Exception) {
                say("Error: ${e.message}")
            }
        }
    }

    fun dislikeAndSkip(track: Track) {
        scope.launch {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { library.dislike(track.id) }
                say("Disliked · skipping")
            } catch (e: Exception) {
                say("Error: ${e.message}")
            }
        }
        val p = player ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem() else if (!_state.value.isWave) p.stop()
    }

    // ------------------------------------------------------------------ internals

    private fun reportPlay(track: Track) {
        val account = library.account.value
        if (!account.isValid) return
        scope.launch(Dispatchers.IO) {
            try {
                api.reportPlayStarted(account, track, UUID.randomUUID().toString())
            } catch (_: Exception) {
                // Listen marks are best-effort, as in the desktop app.
            }
        }
    }

    private fun maybeLoadMore(p: Player) {
        val s = _state.value
        val session = waveSessionId ?: return
        if (!s.isWave || s.loadingMore) return
        val left = p.mediaItemCount - p.currentMediaItemIndex
        if (left > LOAD_MORE_WHEN_LEFT) return
        val generation = queueGeneration
        val wasEnded = p.playbackState == Player.STATE_ENDED
        val oldCount = p.mediaItemCount
        _state.update { it.copy(loadingMore = true) }
        val queue = s.tracks.takeLast(5).map { it.id }
        scope.launch {
            val batch: WaveBatch? = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { library.moreWave(session, queue) }
            } catch (e: Exception) {
                say("Wave: ${e.message}")
                null
            }
            if (generation != queueGeneration) return@launch
            _state.update { it.copy(loadingMore = false) }
            if (batch == null || batch.tracks.isEmpty()) return@launch
            appendTracks(batch.tracks)
            val pl = player ?: return@launch
            if (wasEnded || pl.playbackState == Player.STATE_ENDED) {
                pl.seekTo(oldCount, 0L)
                pl.prepare()
                pl.play()
            }
        }
    }

    companion object {
        /** Ask the wave for more when this many tracks (or fewer) remain after the current one. */
        const val LOAD_MORE_WHEN_LEFT = 2
    }
}
