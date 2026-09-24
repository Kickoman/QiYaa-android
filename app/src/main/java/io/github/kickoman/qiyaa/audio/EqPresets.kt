package io.github.kickoman.qiyaa.audio

/**
 * Winamp's built-in EQ presets (from webamp's presets/builtin.json, MIT).
 * Values are in Winamp's .eqf scale 1..64, where 1 = -12 dB and 64 = +12 dB.
 * Port of src/audio/EqPresets.h.
 */
data class EqPreset(val name: String, val settings: EqSettings, val raw: IntArray)

object EqPresets {
    fun eqfToDb(v: Int): Double = (v - 1.0) / 63.0 * 24.0 - 12.0

    /** Presets in Winamp keep the preamp at "33" (~0 dB); treat that as flat. */
    private fun toDb(v: Int) = if (v == 33) 0.0 else eqfToDb(v)

    private val RAW: List<Pair<String, IntArray>> = listOf(
        "Classical" to intArrayOf(33, 33, 33, 33, 33, 33, 20, 20, 20, 16),
        "Club" to intArrayOf(33, 33, 38, 42, 42, 42, 38, 33, 33, 33),
        "Dance" to intArrayOf(48, 44, 36, 32, 32, 22, 20, 20, 32, 32),
        "Laptop speakers/headphones" to intArrayOf(40, 50, 41, 26, 28, 35, 40, 48, 53, 56),
        "Large hall" to intArrayOf(49, 49, 42, 42, 33, 24, 24, 24, 33, 33),
        "Party" to intArrayOf(44, 44, 33, 33, 33, 33, 33, 33, 44, 44),
        "Pop" to intArrayOf(29, 40, 44, 45, 41, 30, 28, 28, 29, 29),
        "Reggae" to intArrayOf(33, 33, 31, 22, 33, 43, 43, 33, 33, 33),
        "Rock" to intArrayOf(45, 40, 23, 19, 26, 39, 47, 50, 50, 50),
        "Soft" to intArrayOf(40, 35, 30, 28, 30, 39, 46, 48, 50, 52),
        "Ska" to intArrayOf(28, 24, 25, 31, 39, 42, 47, 48, 50, 48),
        "Full Bass" to intArrayOf(48, 48, 48, 42, 35, 25, 18, 15, 14, 14),
        "Soft Rock" to intArrayOf(39, 39, 36, 31, 25, 23, 26, 31, 37, 47),
        "Full Treble" to intArrayOf(16, 16, 16, 25, 37, 50, 58, 58, 58, 60),
        "Full Bass & Treble" to intArrayOf(44, 42, 33, 20, 24, 35, 46, 50, 52, 52),
        "Live" to intArrayOf(24, 33, 39, 41, 42, 42, 39, 37, 37, 36),
        "Techno" to intArrayOf(45, 42, 33, 23, 24, 33, 45, 48, 48, 47),
    )

    const val PREAMP_RAW = 33

    val builtin: List<EqPreset> = RAW.map { (name, bands) ->
        EqPreset(name, EqSettings(enabled = true, preampDb = toDb(PREAMP_RAW), bandsDb = DoubleArray(EqSettings.BANDS) { toDb(bands[it]) }), bands)
    }

    fun byName(name: String): EqPreset? = builtin.firstOrNull { it.name == name }
}
