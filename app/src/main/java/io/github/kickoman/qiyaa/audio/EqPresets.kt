package io.github.kickoman.qiyaa.audio

data class EqPreset(val name: String, val settings: EqSettings, val eqfLevels: List<Int>)

object EqPresets {
    const val EQF_MIN = 1
    const val EQF_MAX = 64
    const val EQF_FLAT = 33

    fun eqfToDb(level: Int): Double =
        (level - EQF_MIN).toDouble() / (EQF_MAX - EQF_MIN) * 2 * EqSettings.MAX_DB - EqSettings.MAX_DB

    private fun levelToDb(level: Int) = if (level == EQF_FLAT) 0.0 else eqfToDb(level)

    private val WINAMP_EQF: List<Pair<String, List<Int>>> =
        listOf(
            "Classical" to listOf(33, 33, 33, 33, 33, 33, 20, 20, 20, 16),
            "Club" to listOf(33, 33, 38, 42, 42, 42, 38, 33, 33, 33),
            "Dance" to listOf(48, 44, 36, 32, 32, 22, 20, 20, 32, 32),
            "Laptop speakers/headphones" to listOf(40, 50, 41, 26, 28, 35, 40, 48, 53, 56),
            "Large hall" to listOf(49, 49, 42, 42, 33, 24, 24, 24, 33, 33),
            "Party" to listOf(44, 44, 33, 33, 33, 33, 33, 33, 44, 44),
            "Pop" to listOf(29, 40, 44, 45, 41, 30, 28, 28, 29, 29),
            "Reggae" to listOf(33, 33, 31, 22, 33, 43, 43, 33, 33, 33),
            "Rock" to listOf(45, 40, 23, 19, 26, 39, 47, 50, 50, 50),
            "Soft" to listOf(40, 35, 30, 28, 30, 39, 46, 48, 50, 52),
            "Ska" to listOf(28, 24, 25, 31, 39, 42, 47, 48, 50, 48),
            "Full Bass" to listOf(48, 48, 48, 42, 35, 25, 18, 15, 14, 14),
            "Soft Rock" to listOf(39, 39, 36, 31, 25, 23, 26, 31, 37, 47),
            "Full Treble" to listOf(16, 16, 16, 25, 37, 50, 58, 58, 58, 60),
            "Full Bass & Treble" to listOf(44, 42, 33, 20, 24, 35, 46, 50, 52, 52),
            "Live" to listOf(24, 33, 39, 41, 42, 42, 39, 37, 37, 36),
            "Techno" to listOf(45, 42, 33, 23, 24, 33, 45, 48, 48, 47),
        )

    val builtin: List<EqPreset> =
        WINAMP_EQF.map { (name, levels) ->
            val settings = EqSettings(enabled = true, preampDb = 0.0, bandsDb = levels.map(::levelToDb))
            EqPreset(name, settings, levels)
        }

    fun byName(name: String): EqPreset? = builtin.firstOrNull { it.name == name }
}
