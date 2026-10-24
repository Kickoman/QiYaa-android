package io.github.kickoman.qiyaa.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.audio.EqSettings
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context, defaultJamServer: String = "") {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val wasEmpty: Boolean = preferences.all.isEmpty()

    init {
        if (preferences.contains(KEY_OLD_JAM_HOST_KEY)) {
            preferences.edit { remove(KEY_OLD_JAM_HOST_KEY) }
        }
    }

    private val mutableVolume = MutableStateFlow(preferences.getInt(KEY_VOLUME, DEFAULT_VOLUME))
    val volume: StateFlow<Int> = mutableVolume.asStateFlow()

    private val mutableBalance = MutableStateFlow(preferences.getInt(KEY_BALANCE, 0))
    val balance: StateFlow<Int> = mutableBalance.asStateFlow()

    private val mutableVisualizerMode =
        MutableStateFlow(VisualizerMode.fromStored(preferences.getInt(KEY_VISUALIZER_MODE, 0)))
    val visualizerMode: StateFlow<VisualizerMode> = mutableVisualizerMode.asStateFlow()

    private val mutableTimeRemaining = MutableStateFlow(preferences.getBoolean(KEY_TIME_REMAINING, false))
    val timeRemaining: StateFlow<Boolean> = mutableTimeRemaining.asStateFlow()

    private val mutableEqAuto = MutableStateFlow(preferences.getBoolean(KEY_EQ_AUTO, false))
    val eqAuto: StateFlow<Boolean> = mutableEqAuto.asStateFlow()

    private val mutableEqPreset =
        MutableStateFlow(preferences.getString(KEY_EQ_PRESET, DEFAULT_EQ_PRESET).orEmpty())
    val eqPreset: StateFlow<String> = mutableEqPreset.asStateFlow()

    private val mutableEq = MutableStateFlow(loadEq())
    val eq: StateFlow<EqSettings> = mutableEq.asStateFlow()

    private val mutableTheme = MutableStateFlow(AccentTheme.fromKey(preferences.getString(KEY_THEME, null)))
    val theme: StateFlow<AccentTheme> = mutableTheme.asStateFlow()

    private val mutableJamServer =
        MutableStateFlow(preferences.getString(KEY_JAM_SERVER, null) ?: defaultJamServer)
    val jamServer: StateFlow<String> = mutableJamServer.asStateFlow()

    private val mutableLanguage =
        MutableStateFlow(AppLanguage.fromTag(preferences.getString(KEY_LANGUAGE, null)))
    val language: StateFlow<AppLanguage> = mutableLanguage.asStateFlow()

    private val mutableJamWaveFeedback = MutableStateFlow(preferences.getBoolean(KEY_JAM_WAVE_FEEDBACK, true))

    /** HOST-16: rotor feedback to the jam wave's own session. */
    val jamWaveFeedback: StateFlow<Boolean> = mutableJamWaveFeedback.asStateFlow()

    private val mutableJamShareAudio = MutableStateFlow(preferences.getBoolean(KEY_JAM_SHARE_AUDIO, false))

    /** Listening along (spec/jam/listen.md): guests may play the files the host plays. */
    val jamShareAudio: StateFlow<Boolean> = mutableJamShareAudio.asStateFlow()

    private val mutableQuality =
        MutableStateFlow(AudioQuality.fromKey(preferences.getString(KEY_QUALITY, null)))

    /** spec/player/quality.md: on Wi-Fi and other unmetered networks. */
    val quality: StateFlow<AudioQuality> = mutableQuality.asStateFlow()

    private val mutableQualityMetered =
        MutableStateFlow(AudioQuality.fromKey(preferences.getString(KEY_QUALITY_METERED, null)))

    /** On mobile data and other metered networks (QUAL-07). */
    val qualityMetered: StateFlow<AudioQuality> = mutableQualityMetered.asStateFlow()

    private val mutableShuffleAlgorithm =
        MutableStateFlow(ShuffleChoice.fromKey(preferences.getString(KEY_SHUFFLE_ALGORITHM, null)))
    val shuffleAlgorithm: StateFlow<ShuffleChoice> = mutableShuffleAlgorithm.asStateFlow()

    private val mutableContinueWithVibe =
        MutableStateFlow(preferences.getBoolean(KEY_CONTINUE_WITH_VIBE, true))
    val continueWithVibe: StateFlow<Boolean> = mutableContinueWithVibe.asStateFlow()

    private val mutableLocalPermissionAsked =
        MutableStateFlow(preferences.getBoolean(KEY_LOCAL_PERMISSION_ASKED, false))
    val localPermissionAsked: StateFlow<Boolean> = mutableLocalPermissionAsked.asStateFlow()

    private val mutableTelemetryEnabled =
        MutableStateFlow(preferences.getBoolean(KEY_TELEMETRY_ENABLED, true))
    val telemetryEnabled: StateFlow<Boolean> = mutableTelemetryEnabled.asStateFlow()

    val telemetryMachine: String? get() = preferences.getString(KEY_TELEMETRY_MACHINE, null)

    fun setVolume(value: Int) {
        mutableVolume.value = value.coerceIn(0, AudioBus.MAX_VOLUME)
        preferences.edit { putInt(KEY_VOLUME, mutableVolume.value) }
    }

    fun setBalance(value: Int) {
        mutableBalance.value = value.coerceIn(-AudioBus.MAX_BALANCE, AudioBus.MAX_BALANCE)
        preferences.edit { putInt(KEY_BALANCE, mutableBalance.value) }
    }

    fun setVisualizerMode(mode: VisualizerMode) {
        mutableVisualizerMode.value = mode
        preferences.edit { putInt(KEY_VISUALIZER_MODE, mode.storedValue) }
    }

    fun setTimeRemaining(value: Boolean) {
        mutableTimeRemaining.value = value
        preferences.edit { putBoolean(KEY_TIME_REMAINING, value) }
    }

    fun setEqAuto(value: Boolean) {
        mutableEqAuto.value = value
        preferences.edit { putBoolean(KEY_EQ_AUTO, value) }
    }

    fun setEq(settings: EqSettings, presetName: String) {
        mutableEq.value = settings
        mutableEqPreset.value = presetName
        preferences.edit {
            putBoolean(KEY_EQ_ENABLED, settings.enabled)
            putFloat(KEY_EQ_PREAMP, settings.preampDb.toFloat())
            putString(KEY_EQ_BANDS, settings.bandsDb.joinToString(",") { "%.1f".format(Locale.ROOT, it) })
            putString(KEY_EQ_PRESET, presetName)
        }
    }

    fun setJamServer(url: String) {
        mutableJamServer.value = url.trim()
        preferences.edit { putString(KEY_JAM_SERVER, mutableJamServer.value) }
    }

    fun setJamWaveFeedback(value: Boolean) {
        mutableJamWaveFeedback.value = value
        preferences.edit { putBoolean(KEY_JAM_WAVE_FEEDBACK, value) }
    }

    fun setLanguage(language: AppLanguage) {
        mutableLanguage.value = language
        preferences.edit { putString(KEY_LANGUAGE, language.tag) }
    }

    fun setJamShareAudio(value: Boolean) {
        mutableJamShareAudio.value = value
        preferences.edit { putBoolean(KEY_JAM_SHARE_AUDIO, value) }
    }

    fun setQuality(quality: AudioQuality) {
        mutableQuality.value = quality
        preferences.edit { putString(KEY_QUALITY, quality.key) }
    }

    fun setQualityMetered(quality: AudioQuality) {
        mutableQualityMetered.value = quality
        preferences.edit { putString(KEY_QUALITY_METERED, quality.key) }
    }

    fun setShuffleAlgorithm(choice: ShuffleChoice) {
        mutableShuffleAlgorithm.value = choice
        preferences.edit { putString(KEY_SHUFFLE_ALGORITHM, choice.key) }
    }

    fun setContinueWithVibe(value: Boolean) {
        mutableContinueWithVibe.value = value
        preferences.edit { putBoolean(KEY_CONTINUE_WITH_VIBE, value) }
    }

    fun setLocalPermissionAsked() {
        mutableLocalPermissionAsked.value = true
        preferences.edit { putBoolean(KEY_LOCAL_PERMISSION_ASKED, true) }
    }

    fun setTelemetryEnabled(value: Boolean) {
        mutableTelemetryEnabled.value = value
        preferences.edit { putBoolean(KEY_TELEMETRY_ENABLED, value) }
    }

    fun setTelemetryMachine(id: String) {
        preferences.edit { putString(KEY_TELEMETRY_MACHINE, id) }
    }

    fun markTelemetryFirstStart() {
        if (preferences.contains(KEY_TELEMETRY_FIRST_START)) return
        preferences.edit(commit = true) { putString(KEY_TELEMETRY_FIRST_START, LocalDate.now().toString()) }
    }

    fun setTheme(theme: AccentTheme) {
        mutableTheme.value = theme
        preferences.edit { putString(KEY_THEME, theme.key) }
    }

    private fun loadEq(): EqSettings {
        val bands =
            preferences.getString(KEY_EQ_BANDS, null)
                ?.split(',')
                ?.map { it.trim().toDoubleOrNull() ?: 0.0 }
                ?.takeIf { it.size == EqSettings.BAND_COUNT }
                ?: List(EqSettings.BAND_COUNT) { 0.0 }
        return EqSettings(
            enabled = preferences.getBoolean(KEY_EQ_ENABLED, true),
            preampDb = preferences.getFloat(KEY_EQ_PREAMP, 0f).toDouble(),
            bandsDb = bands,
        )
    }

    companion object {
        const val FILE = "settings"
        const val KEY_VOLUME = "volume"
        const val KEY_BALANCE = "balance"
        const val KEY_VISUALIZER_MODE = "vis/mode"
        const val KEY_TIME_REMAINING = "time/remaining"
        const val KEY_EQ_AUTO = "equalizer/auto"
        const val KEY_EQ_PRESET = "equalizer/preset"
        const val KEY_EQ_ENABLED = "equalizer/enabled"
        const val KEY_EQ_PREAMP = "equalizer/preamp"
        const val KEY_EQ_BANDS = "equalizer/bands"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
        const val KEY_JAM_SERVER = "jam/server"

        /** Removed on start: jams need no host key since 2026-10-01 (0.2.3 stored one). */
        const val KEY_OLD_JAM_HOST_KEY = "jam/hostKey"
        const val KEY_JAM_WAVE_FEEDBACK = "jam/waveFeedback"
        const val KEY_JAM_SHARE_AUDIO = "jam/shareAudio"
        const val KEY_QUALITY = "playback/quality"
        const val KEY_QUALITY_METERED = "playback/qualityMetered"
        const val KEY_CONTINUE_WITH_VIBE = "playback/continueWithVibe"
        const val KEY_SHUFFLE_ALGORITHM = "shuffle/algorithm"
        const val KEY_TELEMETRY_ENABLED = "telemetry/enabled"
        const val KEY_LOCAL_PERMISSION_ASKED = "local/permissionAsked"
        const val KEY_TELEMETRY_MACHINE = "telemetry/machine"
        const val KEY_TELEMETRY_FIRST_START = "telemetry/firstStart"
        const val DEFAULT_VOLUME = 75
        const val DEFAULT_EQ_PRESET = "Flat"

        /** The stored language, before the graph exists (attachBaseContext). */
        fun storedLanguage(context: Context): AppLanguage = AppLanguage.fromTag(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null),
        )
    }
}
