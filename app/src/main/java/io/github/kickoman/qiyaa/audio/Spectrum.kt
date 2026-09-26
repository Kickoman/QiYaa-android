package io.github.kickoman.qiyaa.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Spectrum(val barCount: Int = DEFAULT_BAR_COUNT) {
    val levels = FloatArray(barCount)
    val peaks = FloatArray(barCount)
    private val peakAge = IntArray(barCount)

    fun reset() {
        levels.fill(0f)
        peaks.fill(0f)
        peakAge.fill(0)
    }

    fun update(spectrumDb: FloatArray, fftSize: Int, sampleRate: Int) {
        val binHz = sampleRate.toDouble() / fftSize
        val lowHz = LOW_HZ
        val highHz = min(HIGH_HZ, sampleRate / 2.0)
        val binCount = spectrumDb.size
        for (bar in 0 until barCount) {
            val fromHz = lowHz * (highHz / lowHz).pow(bar.toDouble() / barCount)
            val toHz = lowHz * (highHz / lowHz).pow((bar + 1).toDouble() / barCount)
            val fromBin = (fromHz / binHz).toInt().coerceIn(1, binCount - 1)
            val toBin = ceil(toHz / binHz).toInt().coerceIn(fromBin + 1, binCount)
            var peakDb = FLOOR_DB
            for (bin in fromBin until toBin) peakDb = max(peakDb, spectrumDb[bin])
            val target = ((peakDb - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0f, 1f)
            levels[bar] = max(target, levels[bar] - LEVEL_FALL_PER_FRAME)
            var peak = peaks[bar] - PEAK_GRAVITY * peakAge[bar] * peakAge[bar]
            if (peak < levels[bar]) {
                peak = levels[bar]
                peakAge[bar] = 0
            } else {
                peakAge[bar]++
            }
            peaks[bar] = max(peak, 0f)
        }
    }

    companion object {
        const val DEFAULT_BAR_COUNT = 19
        const val LOW_HZ = 60.0
        const val HIGH_HZ = 16_000.0
        const val FLOOR_DB = -72f
        const val CEILING_DB = -6f
        const val LEVEL_FALL_PER_FRAME = 0.07f
        const val PEAK_GRAVITY = 0.0004f
    }
}
