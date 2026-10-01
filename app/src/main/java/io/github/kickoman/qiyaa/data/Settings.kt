package io.github.kickoman.qiyaa.data

import android.content.Context
import android.content.SharedPreferences
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.audio.EqSettings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context, defaultJamServer: String = "") {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { stored ->
            if (stored.contains(KEY_OLD_JAM_HOST_KEY)) stored.edit().remove(KEY_OLD_JAM_HOST_KEY).apply()
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

    private val mutableJamWaveFeedback = MutableStateFlow(preferences.getBoolean(KEY_JAM_WAVE_FEEDBACK, true))

    /** HOST-16: rotor feedback to the jam wave's own session. */
    val jamWaveFeedback: StateFlow<Boolean> = mutableJamWaveFeedback.asStateFlow()

    fun setVolume(value: Int) {
        mutableVolume.value = value.coerceIn(0, AudioBus.MAX_VOLUME)
        preferences.edit().putInt(KEY_VOLUME, mutableVolume.value).apply()
    }

    fun setBalance(value: Int) {
        mutableBalance.value = value.coerceIn(-AudioBus.MAX_BALANCE, AudioBus.MAX_BALANCE)
        preferences.edit().putInt(KEY_BALANCE, mutableBalance.value).apply()
    }

    fun setVisualizerMode(mode: VisualizerMode) {
        mutableVisualizerMode.value = mode
        preferences.edit().putInt(KEY_VISUALIZER_MODE, mode.storedValue).apply()
    }

    fun setTimeRemaining(value: Boolean) {
        mutableTimeRemaining.value = value
        preferences.edit().putBoolean(KEY_TIME_REMAINING, value).apply()
    }

    fun setEqAuto(value: Boolean) {
        mutableEqAuto.value = value
        preferences.edit().putBoolean(KEY_EQ_AUTO, value).apply()
    }

    fun setEq(settings: EqSettings, presetName: String) {
        mutableEq.value = settings
        mutableEqPreset.value = presetName
        preferences.edit()
            .putBoolean(KEY_EQ_ENABLED, settings.enabled)
            .putFloat(KEY_EQ_PREAMP, settings.preampDb.toFloat())
            .putString(KEY_EQ_BANDS, settings.bandsDb.joinToString(",") { "%.1f".format(Locale.ROOT, it) })
            .putString(KEY_EQ_PRESET, presetName)
            .apply()
    }

    fun setJamServer(url: String) {
        mutableJamServer.value = url.trim()
        preferences.edit().putString(KEY_JAM_SERVER, mutableJamServer.value).apply()
    }

    fun setJamWaveFeedback(value: Boolean) {
        mutableJamWaveFeedback.value = value
        preferences.edit().putBoolean(KEY_JAM_WAVE_FEEDBACK, value).apply()
    }

    fun setTheme(theme: AccentTheme) {
        mutableTheme.value = theme
        preferences.edit().putString(KEY_THEME, theme.key).apply()
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
        const val KEY_JAM_SERVER = "jam/server"

        /** Removed on start: jams need no host key since 2026-10-01 (0.2.3 stored one). */
        const val KEY_OLD_JAM_HOST_KEY = "jam/hostKey"
        const val KEY_JAM_WAVE_FEEDBACK = "jam/waveFeedback"
        const val DEFAULT_VOLUME = 75
        const val DEFAULT_EQ_PRESET = "Flat"
    }
}
