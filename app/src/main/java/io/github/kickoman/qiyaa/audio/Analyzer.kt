package io.github.kickoman.qiyaa.audio

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Port of the Analyzer in src/vis/Visualizers.cpp: Hann window + in-place radix-2 FFT,
 * output is N/2+1 magnitudes in dBFS (a full-scale sine reads 0 dBFS).
 */
class Analyzer(val size: Int = 1024) {
    init {
        require(size >= 8 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    private val window = FloatArray(size) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))).toFloat() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cosT = FloatArray(size / 2) { k -> cos(2.0 * Math.PI * k / size).toFloat() }
    private val sinT = FloatArray(size / 2) { k -> kotlin.math.sin(2.0 * Math.PI * k / size).toFloat() }

    /** [mono] must hold at least [size] samples; [outDb] gets size/2+1 values. */
    fun analyze(mono: FloatArray, outDb: FloatArray) {
        for (i in 0 until size) {
            re[i] = mono[i] * window[i]
            im[i] = 0f
        }
        fft()
        val norm = 4f / size
        for (k in 0..size / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
            outDb[k] = 20f * log10(max(mag, 1e-9f))
        }
    }

    private fun fft() {
        val n = size
        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cosT[k]
                    val wi = -sinT[k]
                    val a = i + m
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
