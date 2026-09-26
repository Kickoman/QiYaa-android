package io.github.kickoman.qiyaa.ui

import android.app.Application
import android.content.ComponentName
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.kickoman.qiyaa.R
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    val settings = graph.settings
    val audioBus = graph.audioBus
    val queue = graph.queue
    val library = graph.library

    private val mutableUi = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = mutableUi.asStateFlow()

    private val mutableNotices = MutableSharedFlow<String>(extraBufferCapacity = NOTICE_BUFFER)
    val notices: SharedFlow<String> = mutableNotices.asSharedFlow()

    val eqPreset: StateFlow<String> = settings.eqPreset

    private val mutablePresetsOpen = MutableStateFlow(false)
    val presetsOpen: StateFlow<Boolean> = mutablePresetsOpen.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val listener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    queue.syncFromPlayer()
                }
                refresh(player)
            }
        }

    init {
        connect()
        viewModelScope.launch { settings.volume.collect { controller?.volume = AudioBus.volumeGain(it) } }
        viewModelScope.launch { settings.balance.collect { audioBus.setBalance(it) } }
        viewModelScope.launch { settings.eq.collect { audioBus.equalizer.publish(it) } }
    }

    fun togglePlay() {
        val current = controller ?: return
        if (current.mediaItemCount == 0) {
            notify(string(R.string.player_nothing_to_play))
            return
        }
        if (current.isPlaying || (current.playWhenReady && current.playbackState == Player.STATE_BUFFERING)) {
            current.pause()
        } else {
            if (current.playbackState == Player.STATE_IDLE) current.prepare()
            if (current.playbackState == Player.STATE_ENDED) current.seekTo(0, 0)
            current.play()
        }
    }

    fun stop() {
        val current = controller ?: return
        current.pause()
        current.seekTo(0)
    }

    fun next() {
        val current = controller ?: return
        when {
            current.hasNextMediaItem() -> current.seekToNextMediaItem()
            queue.state.value.isWave -> notify(string(R.string.playlist_loading_more_toast))
            else -> stop()
        }
    }

    fun previous() {
        val current = controller ?: return
        when {
            current.currentPosition > RESTART_AFTER_MS -> current.seekTo(0)
            current.hasPreviousMediaItem() -> current.seekToPreviousMediaItem()
            else -> current.seekTo(0)
        }
    }

    fun playIndex(index: Int) {
        val current = controller ?: return
        if (index !in 0 until current.mediaItemCount) return
        current.seekTo(index, 0)
        if (current.playbackState == Player.STATE_IDLE) current.prepare()
        current.play()
    }

    fun seekToFraction(fraction: Float) {
        val current = controller ?: return
        val durationMs = mutableUi.value.durationMs
        if (durationMs <= 0) return
        val positionMs = (durationMs * fraction.coerceIn(0f, 1f)).toLong()
        current.seekTo(positionMs)
        mutableUi.update { it.copy(positionMs = positionMs) }
    }

    fun previewPosition(fraction: Float) {
        val durationMs = mutableUi.value.durationMs
        if (durationMs >
            0
        ) {
            mutableUi.update { it.copy(positionMs = (durationMs * fraction.coerceIn(0f, 1f)).toLong()) }
        }
    }

    fun toggleShuffle() {
        val current = controller ?: return
        current.shuffleModeEnabled = !current.shuffleModeEnabled
        notify(
            string(
                if (current.shuffleModeEnabled) R.string.player_shuffle_on else R.string.player_shuffle_off,
            ),
        )
    }

    fun toggleRepeat() {
        val current = controller ?: return
        val on = current.repeatMode == Player.REPEAT_MODE_OFF
        current.repeatMode = if (on) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        notify(string(if (on) R.string.player_repeat_on else R.string.player_repeat_off))
    }

    fun toggleMiniPlay() {
        val current = controller ?: return
        if (current.isPlaying) current.pause() else togglePlay()
    }

    fun setVolume(value: Int) = settings.setVolume(value)

    fun setBalance(value: Int) = settings.setBalance(value)

    fun cycleVisualizer() = settings.setVisualizerMode(settings.visualizerMode.value.next())

    fun toggleRemaining() = settings.setTimeRemaining(!settings.timeRemaining.value)

    fun cycleTheme() {
        val next = settings.theme.value.next()
        settings.setTheme(next)
        notify(string(R.string.player_theme, string(next.labelId())))
    }

    fun toggleLikeCurrent() {
        mutableUi.value.current?.let(queue::toggleLike)
    }

    fun dislikeCurrent() {
        mutableUi.value.current?.let(queue::dislikeAndSkip)
    }

    fun setEqEnabled(on: Boolean) = settings.setEq(settings.eq.value.copy(enabled = on), eqPreset.value)

    fun setEqAuto(on: Boolean) = settings.setEqAuto(on)

    fun setBand(index: Int, db: Double) {
        settings.setEq(settings.eq.value.withBand(index, db), string(R.string.eq_custom))
    }

    fun setPreamp(db: Double) {
        val clamped = db.coerceIn(-EqSettings.MAX_DB, EqSettings.MAX_DB)
        settings.setEq(settings.eq.value.copy(preampDb = clamped), eqPreset.value)
    }

    fun applyPreset(name: String) {
        val preset = EqPresets.byName(name) ?: return
        settings.setEq(preset.settings.copy(enabled = settings.eq.value.enabled), preset.name)
        mutablePresetsOpen.value = false
        notify(string(R.string.eq_applied, preset.name))
    }

    fun resetFlat() {
        settings.setEq(EqSettings(enabled = settings.eq.value.enabled), string(R.string.eq_flat))
        mutablePresetsOpen.value = false
        notify(string(R.string.eq_applied_flat))
    }

    fun openPresets() {
        mutablePresetsOpen.value = true
    }

    fun closePresets() {
        mutablePresetsOpen.value = false
    }

    override fun onCleared() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
    }

    @OptIn(UnstableApi::class)
    private fun connect() {
        val application = getApplication<Application>()
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val connected =
                try {
                    future.get()
                } catch (failed: Exception) {
                    notify(
                        string(
                            R.string.player_service_unavailable,
                            failed.message ?: failed.javaClass.simpleName,
                        ),
                    )
                    return@addListener
                }
            controller = connected
            connected.addListener(listener)
            connected.volume = AudioBus.volumeGain(settings.volume.value)
            refresh(connected)
        }, ContextCompat.getMainExecutor(application))
    }

    private fun refresh(player: Player) {
        val item = player.currentMediaItem
        val knownDuration = item?.let { MediaItems.toTrack(it).durationMs } ?: 0L
        mutableUi.value =
            PlayerUi(
                index = if (item == null) -1 else player.currentMediaItemIndex,
                count = player.mediaItemCount,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                playbackState = player.playbackState,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { it > 0 } ?: knownDuration,
                shuffle = player.shuffleModeEnabled,
                repeat = player.repeatMode != Player.REPEAT_MODE_OFF,
                current = item?.let(MediaItems::toTrack),
            )
        if (player.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker =
            viewModelScope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    val current = controller ?: break
                    mutableUi.update {
                        it.copy(
                            positionMs = current.currentPosition.coerceAtLeast(0),
                            durationMs = current.duration.takeIf { duration ->
                                duration > 0
                            } ?: it.durationMs,
                        )
                    }
                }
            }
    }

    private fun notify(message: String) {
        mutableNotices.tryEmit(message)
    }

    private fun string(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    companion object {
        const val TICK_MS = 250L
        const val RESTART_AFTER_MS = 3_000L
        private const val NOTICE_BUFFER = 8
    }
}

fun AccentTheme.labelId(): Int = when (this) {
    AccentTheme.CLASSIC_GREEN -> R.string.theme_green
    AccentTheme.AMBER -> R.string.theme_amber
    AccentTheme.ICE_BLUE -> R.string.theme_ice
}
