package io.github.kickoman.qiyaa.data

import android.content.Context
import android.content.SharedPreferences
import io.github.kickoman.qiyaa.audio.EqSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccentTheme(val key: String, val label: String) {
    CLASSIC_GREEN("green", "Classic green"),
    AMBER("amber", "Amber"),
    ICE_BLUE("ice", "Ice blue");

    fun next(): AccentTheme = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AMBER
    }
}

/** Persisted user settings; the same keys and defaults as the desktop app where they apply. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _volume = MutableStateFlow(prefs.getInt("volume", 75))
    val volume: StateFlow<Int> = _volume.asStateFlow()
    fun setVolume(v: Int) = set(_volume, v.coerceIn(0, 100)) { putInt("volume", it) }

    private val _balance = MutableStateFlow(prefs.getInt("balance", 0))
    val balance: StateFlow<Int> = _balance.asStateFlow()
    fun setBalance(v: Int) = set(_balance, v.coerceIn(-100, 100)) { putInt("balance", it) }

    /** 0 = spectrum, 1 = oscilloscope, 2 = off. */
    private val _visMode = MutableStateFlow(prefs.getInt("vis/mode", 0).coerceIn(0, 2))
    val visMode: StateFlow<Int> = _visMode.asStateFlow()
    fun setVisMode(v: Int) = set(_visMode, v.coerceIn(0, 2)) { putInt("vis/mode", it) }

    private val _timeRemaining = MutableStateFlow(prefs.getBoolean("time/remaining", false))
    val timeRemaining: StateFlow<Boolean> = _timeRemaining.asStateFlow()
    fun setTimeRemaining(v: Boolean) = set(_timeRemaining, v) { putBoolean("time/remaining", it) }

    private val _eqAuto = MutableStateFlow(prefs.getBoolean("equalizer/auto", false))
    val eqAuto: StateFlow<Boolean> = _eqAuto.asStateFlow()
    fun setEqAuto(v: Boolean) = set(_eqAuto, v) { putBoolean("equalizer/auto", it) }

    private val _eqPreset = MutableStateFlow(prefs.getString("equalizer/preset", "Flat").orEmpty())
    val eqPreset: StateFlow<String> = _eqPreset.asStateFlow()

    private val _eq = MutableStateFlow(loadEq())
    val eq: StateFlow<EqSettings> = _eq.asStateFlow()
    fun setEq(s: EqSettings, presetName: String) {
        _eq.value = s
        _eqPreset.value = presetName
        prefs.edit()
            .putBoolean("equalizer/enabled", s.enabled)
            .putFloat("equalizer/preamp", s.preampDb.toFloat())
            .putString("equalizer/bands", s.bandsDb.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, it) })
            .putString("equalizer/preset", presetName)
            .apply()
    }

    private val _theme = MutableStateFlow(AccentTheme.fromKey(prefs.getString("theme", null)))
    val theme: StateFlow<AccentTheme> = _theme.asStateFlow()
    fun setTheme(t: AccentTheme) = set(_theme, t) { putString("theme", it.key) }

    private fun loadEq(): EqSettings {
        val bands = prefs.getString("equalizer/bands", null)
            ?.split(',')?.map { it.trim().toDoubleOrNull() ?: 0.0 }
            ?.takeIf { it.size == EqSettings.BANDS }
            ?.toDoubleArray() ?: DoubleArray(EqSettings.BANDS)
        return EqSettings(
            enabled = prefs.getBoolean("equalizer/enabled", true),
            preampDb = prefs.getFloat("equalizer/preamp", 0f).toDouble(),
            bandsDb = bands,
        )
    }

    @android.annotation.SuppressLint("CommitPrefEdits")
    private inline fun <T> set(flow: MutableStateFlow<T>, value: T, write: SharedPreferences.Editor.(T) -> SharedPreferences.Editor) {
        flow.value = value
        prefs.edit().write(value).apply()
    }
}
