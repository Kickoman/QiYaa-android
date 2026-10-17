package io.github.kickoman.qiyaa

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings.Secure
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.kickoman.qiyaa.data.Settings
import io.github.kickoman.qiyaa.playback.AudioFailure
import io.github.kickoman.qiyaa.playback.JamHost
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.queue.QueueActivity
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.telemetry.Beginning
import io.github.kickoman.qiyaa.telemetry.ExitInfo
import io.github.kickoman.qiyaa.telemetry.FeatureName
import io.github.kickoman.qiyaa.telemetry.MachineId
import io.github.kickoman.qiyaa.telemetry.Reporter
import io.github.kickoman.qiyaa.telemetry.Sender
import io.github.kickoman.qiyaa.telemetry.StartFields
import io.github.kickoman.qiyaa.telemetry.TelemetrySession
import io.github.kickoman.qiyaa.telemetry.TelemetryStore
import io.github.kickoman.qiyaa.yandex.SessionState
import io.github.kickoman.qiyaa.yandex.YandexApi
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppTelemetry(
    private val application: Application,
    private val settings: Settings,
    httpClient: OkHttpClient,
    endpoint: String,
    private val scope: CoroutineScope,
) {
    private val onScreen = MutableStateFlow(false)
    private val playing = MutableStateFlow(false)
    private val online = MutableStateFlow(false)
    private val connectedApi = CompletableDeferred<YandexApi>()
    private val sender = Sender(httpClient, endpoint)
    private val store = TelemetryStore(File(application.noBackupFilesDir, FOLDER))
    private val session =
        TelemetrySession(
            enabled = settings.telemetryEnabled,
            onScreen = onScreen,
            playing = playing,
            online = online,
            scope = scope,
            io = Dispatchers.IO,
            store = store,
            newReporter = ::newReporter,
            beginning = ::beginning,
            log = { message -> Log.w(AppGraph.LOG_TAG, message) },
        )

    fun start() {
        application.registerActivityLifecycleCallbacks(ScreenCounter(onScreen))
        session.start()
    }

    fun connect(
        api: YandexApi,
        sessionState: StateFlow<SessionState>,
        queue: QueueController,
        jamHost: JamHost,
        network: StateFlow<Boolean>,
    ) {
        connectedApi.complete(api)
        collect(network) { online.value = it }
        collect(queue.activity) { activity ->
            when (activity) {
                QueueActivity.TrackStarted -> session.countTrack()
                is QueueActivity.PlayingChanged -> playing.value = activity.playing
                is QueueActivity.PlaybackFailed -> record(TelemetryCodes.error(activity.kind))
            }
        }
        collect(api.requestFailures) { record(TelemetryCodes.error(it)) }
        collect(sessionState.map { it == SessionState.Expired }.distinctUntilChanged()) { expired ->
            if (expired) loginFailed()
        }
        collect(jamHost.events) { event -> TelemetryCodes.error(event)?.let(::record) }
        val jamActive = jamHost.state.map { it.phase == JamHostPhase.ACTIVE }.distinctUntilChanged()
        collect(jamActive.dropWhile { !it }) { active ->
            session.recordFeature(FeatureName.JAM, TelemetryCodes.onOff(active))
            if (active) session.countJam()
        }
        collect(settings.language.drop(1)) {
            session.recordFeature(FeatureName.LANGUAGE, TelemetryCodes.language(it).code)
        }
        collect(settings.eq.map { it.enabled }.distinctUntilChanged().drop(1)) {
            session.recordFeature(FeatureName.EQUALIZER, TelemetryCodes.onOff(it))
        }
        collect(settings.visualizerMode.drop(1)) {
            session.recordFeature(FeatureName.VIS, TelemetryCodes.vis(it).code)
        }
    }

    fun loginFailed() = record(TelemetryCodes.LOGIN_FAILED)

    fun audioFailed(failure: AudioFailure) = record(TelemetryCodes.error(failure))

    private fun record(error: ErrorCode) = session.recordError(error.area, error.kind, error.httpStatus)

    private fun <T> collect(flow: Flow<T>, action: suspend (T) -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect(action) }
    }

    private fun newReporter(): Reporter {
        val reporter =
            Reporter(
                version = BuildConfig.VERSION_NAME,
                store = store,
                post = sender::post,
                scope = scope,
                io = Dispatchers.IO,
                now = Instant::now,
                uptimeMs = { SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() },
                log = { message -> Log.i(AppGraph.LOG_TAG, "Telemetry: $message") },
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            application.getSystemService(ActivityManager::class.java)
                .setProcessStateSummary(reporter.session.toByteArray(Charsets.UTF_8))
        }
        return reporter
    }

    private suspend fun beginning(): Beginning {
        val loggedIn = connectedApi.await().token.isNotEmpty()
        if (!settings.markTelemetryFirstStart()) {
            Log.w(AppGraph.LOG_TAG, "Telemetry: ${Settings.KEY_TELEMETRY_FIRST_START} was not saved")
        }
        val machine = MachineId.resolve(androidId(), settings.telemetryMachine, settings::setTelemetryMachine)
        val exits = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) exits() else emptyMap()
        return Beginning(startFields(loggedIn), machine, exits)
    }

    private fun startFields(loggedIn: Boolean): StartFields {
        val system = Resources.getSystem().configuration.locales[0]
        return StartFields(
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            device = TelemetryCodes.device(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
            kernel = System.getProperty("os.version").orEmpty(),
            arch = TelemetryCodes.arch(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
            packaging = TelemetryCodes.packaging(BuildConfig.BUILD_TYPE),
            locale = TelemetryCodes.locale(system?.language.orEmpty(), system?.country.orEmpty()),
            language = TelemetryCodes.language(settings.language.value),
            dpr = application.resources.displayMetrics.density.toDouble(),
            theme = TelemetryCodes.theme(settings.theme.value),
            vis = TelemetryCodes.vis(settings.visualizerMode.value),
            equalizer = settings.eq.value.enabled,
            quality = TelemetryCodes.quality(settings.quality.value),
            qualityMetered = TelemetryCodes.quality(settings.qualityMetered.value),
            loggedIn = loggedIn,
            firstRun = settings.wasEmpty,
        )
    }

    @SuppressLint("HardwareIds") // hashed by MachineId and never sent or stored: spec/telemetry
    private fun androidId(): String? = Secure.getString(application.contentResolver, Secure.ANDROID_ID)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exits(): Map<String, ExitInfo> {
        val manager = application.getSystemService(ActivityManager::class.java)
        val records = manager.getHistoricalProcessExitReasons(application.packageName, 0, MAX_EXITS)
        return TelemetryCodes.exits(
            records.map {
                ProcessExit(it.processStateSummary?.toString(Charsets.UTF_8), it.reason, it.status)
            },
        )
    }

    private class ScreenCounter(private val onScreen: MutableStateFlow<Boolean>) :
        Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            onScreen.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (!activity.isChangingConfigurations) onScreen.value = started > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        const val FOLDER = "telemetry"
        const val MAX_EXITS = 16
    }
}
