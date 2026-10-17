package io.github.kickoman.qiyaa.telemetry

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Beginning(
    val start: StartFields,
    val machine: String,
    val exits: Map<String, ExitInfo> = emptyMap(),
)

class TelemetrySession(
    private val enabled: Flow<Boolean>,
    private val onScreen: Flow<Boolean>,
    private val playing: Flow<Boolean>,
    private val online: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val store: TelemetryStore,
    private val newReporter: () -> Reporter,
    private val beginning: suspend () -> Beginning,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var current: Reporter? = null
    private var inUseNow = false
    private var playingNow = false
    private var onlineNow = false
    private var ticker: Job? = null

    val reporter: Reporter? get() = synchronized(lock) { current }

    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { online.collect(::onOnline) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(onScreen, playing, ::Pair).collect { (screen, play) -> onUse(screen, play) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            enabled.distinctUntilChanged().collect { on -> if (on) turnOn() else turnOff() }
        }
    }

    fun recordError(area: ErrorArea, kind: String, httpStatus: Int? = null) {
        reporter?.recordError(area, kind, httpStatus)
    }

    fun recordFeature(name: FeatureName, value: String) {
        reporter?.recordFeature(name, value)
    }

    fun countTrack() {
        reporter?.countTrack()
    }

    fun countJam() {
        reporter?.countJam()
    }

    private suspend fun turnOn() {
        val created = newReporter()
        created.install()
        synchronized(lock) {
            current = created
            created.setInUse(inUseNow)
            created.setPlaying(playingNow)
        }
        val first =
            try {
                withContext(io) { beginning() }
            } catch (failed: Exception) {
                if (failed is CancellationException) throw failed
                log("Telemetry did not start: ${failed.javaClass.name}")
                return
            }
        created.begin(first.start, first.machine, first.exits)
        sendIfOnline()
    }

    private suspend fun turnOff() {
        val forgotten = synchronized(lock) { current.also { current = null } }
        if (forgotten != null) {
            forgotten.forget()
        } else {
            withContext(NonCancellable + io) { store.removeAll() }
        }
    }

    private fun onUse(screen: Boolean, play: Boolean) {
        val inUse = screen || play
        val (active, left) =
            synchronized(lock) {
                playingNow = play
                current?.setPlaying(play)
                val changed = inUse != inUseNow
                inUseNow = inUse
                current?.setInUse(inUse)
                if (changed && inUse) startTicker()
                if (changed && !inUse) stopTicker()
                current to (changed && !inUse)
            }
        if (left && active != null) {
            active.saveMarker()
            sendIfOnline()
        }
    }

    private fun onOnline(on: Boolean) {
        val returned =
            synchronized(lock) {
                val back = on && !onlineNow
                onlineNow = on
                back
            }
        if (returned) sendIfOnline()
    }

    private fun startTicker() {
        ticker =
            scope.launch {
                var minutes = 0
                while (true) {
                    delay(MARKER_INTERVAL_MS)
                    minutes++
                    reporter?.saveMarker()
                    if (minutes % SEND_EVERY_MINUTES == 0) sendIfOnline()
                }
            }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun sendIfOnline() {
        val target = synchronized(lock) { current.takeIf { onlineNow } } ?: return
        scope.launch { target.send() }
    }

    companion object {
        const val MARKER_INTERVAL_MS = 60_000L
        const val SEND_EVERY_MINUTES = 5
    }
}
