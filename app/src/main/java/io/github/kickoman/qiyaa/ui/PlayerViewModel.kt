package io.github.kickoman.qiyaa.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.audio.EqPresets
import io.github.kickoman.qiyaa.audio.EqSettings
import io.github.kickoman.qiyaa.data.AccentTheme
import io.github.kickoman.qiyaa.playback.MediaItems
import io.github.kickoman.qiyaa.playback.PlaybackService
import io.github.kickoman.qiyaa.yandex.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Snapshot of ExoPlayer state for the Player screen. */
data class PlayerUi(
    val index: Int = -1,
    val count: Int = 0,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val current: Track? = null,
) {
    val hasTrack: Boolean get() = current != null
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING && playWhenReady
}

/** Transport + audio settings; talks to the service through a MediaController. */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.appGraph
    val settings = graph.settings
    val audioBus = graph.audioBus
    val queue = graph.queue
    val library = graph.library

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    /** Name of the current EQ preset ("Custom" when bands were touched). */
    val eqPreset: StateFlow<String> = settings.eqPreset
    private val _presetsOpen = MutableStateFlow(false)
    val presetsOpen: StateFlow<Boolean> = _presetsOpen.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) queue.syncFromPlayer()
            refresh(player)
        }
    }

    init {
        connect()
        viewModelScope.launch {
            settings.volume.collect { v -> controller?.volume = AudioBus.volumeGain(v) }
        }
        viewModelScope.launch { settings.balance.collect { audioBus.setBalance(it) } }
        viewModelScope.launch { settings.eq.collect { audioBus.eq.publish(it) } }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun connect() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                queue.say("Player service unavailable: ${e.message}")
                return@addListener
            }
            controller = c
            c.addListener(listener)
            c.volume = AudioBus.volumeGain(settings.volume.value)
            refresh(c)
        }, ContextCompat.getMainExecutor(app))
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        _ui.value = PlayerUi(
            index = if (item == null) -1 else p.currentMediaItemIndex,
            count = p.mediaItemCount,
            isPlaying = p.isPlaying,
            playWhenReady = p.playWhenReady,
            playbackState = p.playbackState,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: (item?.let { MediaItems.toTrack(it).durationMs } ?: 0L),
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode != Player.REPEAT_MODE_OFF,
            current = item?.let(MediaItems::toTrack),
        )
        if (p.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val c = controller ?: break
                _ui.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = c.duration.takeIf { d -> d > 0 } ?: it.durationMs) }
            }
        }
    }

    // ------------------------------------------------------------------ transport

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            queue.say(getApplication<Application>().getString(io.github.kickoman.qiyaa.R.string.player_nothing_to_play))
            return
        }
        if (c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.play()
        }
    }

    fun stop() {
        val c = controller ?: return
        c.pause()
        c.seekTo(0)
    }

    /** Past the last track: a wave is still loading more, a finite queue stops (as in Winamp). */
    fun next() {
        val c = controller ?: return
        when {
            c.hasNextMediaItem() -> c.seekToNextMediaItem()
            queue.state.value.isWave -> queue.say(str(io.github.kickoman.qiyaa.R.string.playlist_loading_more_toast))
            else -> stop()
        }
    }

    /** Winamp-like: within the first 3 s go to the previous track, otherwise restart. */
    fun prev() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0)
        else if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
        else c.seekTo(0)
    }

    fun playIndex(i: Int) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        c.seekTo(i, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun seekToFraction(f: Float) {
        val c = controller ?: return
        val d = _ui.value.durationMs
        if (d <= 0) return
        val pos = (d * f.coerceIn(0f, 1f)).toLong()
        c.seekTo(pos)
        _ui.update { it.copy(positionMs = pos) }
    }

    /** Live position preview while dragging the seek bar (no seek yet). */
    fun previewPosition(f: Float) {
        val d = _ui.value.durationMs
        if (d > 0) _ui.update { it.copy(positionMs = (d * f.coerceIn(0f, 1f)).toLong()) }
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        queue.say(str(if (c.shuffleModeEnabled) io.github.kickoman.qiyaa.R.string.player_shuffle_on else io.github.kickoman.qiyaa.R.string.player_shuffle_off))
    }

    fun toggleRepeat() {
        val c = controller ?: return
        val on = c.repeatMode == Player.REPEAT_MODE_OFF
        c.repeatMode = if (on) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        queue.say(str(if (on) io.github.kickoman.qiyaa.R.string.player_repeat_on else io.github.kickoman.qiyaa.R.string.player_repeat_off))
    }

    fun toggleMiniPlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else togglePlay()
    }

    // ------------------------------------------------------------------ settings

    fun setVolume(v: Int) = settings.setVolume(v)
    fun setBalance(v: Int) = settings.setBalance(v)
    fun cycleVis() = settings.setVisMode((settings.visMode.value + 1) % 3)
    fun toggleRemaining() = settings.setTimeRemaining(!settings.timeRemaining.value)

    fun cycleTheme() {
        val next = settings.theme.value.next()
        settings.setTheme(next)
        queue.say(str(io.github.kickoman.qiyaa.R.string.player_theme, next.label))
    }

    fun toggleLikeCurrent() {
        _ui.value.current?.let(queue::toggleLike)
    }

    fun dislikeCurrent() {
        _ui.value.current?.let(queue::dislikeAndSkip)
    }

    // ------------------------------------------------------------------ equalizer

    fun setEqEnabled(on: Boolean) = settings.setEq(settings.eq.value.copy(enabled = on), eqPreset.value)
    fun setEqAuto(on: Boolean) = settings.setEqAuto(on)

    fun setBand(i: Int, db: Double) {
        val s = settings.eq.value
        val bands = s.bandsDb.copyOf().also { it[i] = db.coerceIn(-EqSettings.MAX_DB, EqSettings.MAX_DB) }
        settings.setEq(s.copy(bandsDb = bands), str(io.github.kickoman.qiyaa.R.string.eq_custom))
    }

    fun setPreamp(db: Double) {
        settings.setEq(settings.eq.value.copy(preampDb = db.coerceIn(-EqSettings.MAX_DB, EqSettings.MAX_DB)), eqPreset.value)
    }

    fun applyPreset(name: String) {
        val p = EqPresets.byName(name) ?: return
        settings.setEq(p.settings.copy(enabled = settings.eq.value.enabled), p.name)
        _presetsOpen.value = false
        queue.say(str(io.github.kickoman.qiyaa.R.string.eq_applied, p.name))
    }

    fun resetFlat() {
        settings.setEq(EqSettings(enabled = settings.eq.value.enabled), str(io.github.kickoman.qiyaa.R.string.eq_flat))
        _presetsOpen.value = false
        queue.say(str(io.github.kickoman.qiyaa.R.string.eq_applied_flat))
    }

    fun openPresets() { _presetsOpen.value = true }
    fun closePresets() { _presetsOpen.value = false }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    override fun onCleared() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
    }
}
