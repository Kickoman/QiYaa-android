package io.github.kickoman.qiyaa.audio

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Port of the Spectrum visualizer in src/vis/Visualizers.cpp: 19 logarithmic bars between
 * 60 Hz and 16 kHz with Winamp-like falloff and peak markers. Values are 0..1.
 */
class Spectrum(val bars: Int = 19) {
    val levels = FloatArray(bars)
    val peaks = FloatArray(bars)
    private val peakFrames = IntArray(bars)

    fun reset() {
        levels.fill(0f)
        peaks.fill(0f)
        peakFrames.fill(0)
    }

    /** [spectrumDb] has fftSize/2+1 entries in dBFS. */
    fun update(spectrumDb: FloatArray, fftSize: Int, sampleRate: Int) {
        val binHz = sampleRate.toDouble() / fftSize
        val lo = 60.0
        val hi = min(16000.0, sampleRate / 2.0)
        val bins = spectrumDb.size
        for (b in 0 until bars) {
            val f0 = lo * (hi / lo).pow(b.toDouble() / bars)
            val f1 = lo * (hi / lo).pow((b + 1).toDouble() / bars)
            val i0 = (f0 / binHz).toInt().coerceIn(1, bins - 1)
            val i1 = ceil(f1 / binHz).toInt().coerceIn(i0 + 1, bins)
            var peakDb = MIN_DB
            for (i in i0 until i1) peakDb = max(peakDb, spectrumDb[i])
            val target = ((peakDb - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
            // Bars jump up and fall smoothly, like Winamp's "fast" falloff.
            levels[b] = max(target, levels[b] - 0.07f)
            var peak = peaks[b] - 0.0004f * peakFrames[b] * peakFrames[b]
            if (peak < levels[b]) {
                peak = levels[b]
                peakFrames[b] = 0
            } else {
                peakFrames[b]++
            }
            peaks[b] = max(peak, 0f)
        }
    }

    companion object {
        const val MIN_DB = -72f
        const val MAX_DB = -6f
    }
}
