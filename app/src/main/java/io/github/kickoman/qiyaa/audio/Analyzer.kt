package io.github.kickoman.qiyaa.audio

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class Analyzer(val size: Int = DEFAULT_SIZE) {
    init {
        require(size >= 8 && size and (size - 1) == 0) { "FFT size must be a power of two, got $size" }
    }

    val binCount: Int = size / 2 + 1

    private val window = FloatArray(size) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))).toFloat() }
    private val real = FloatArray(size)
    private val imaginary = FloatArray(size)
    private val cosTable = FloatArray(size / 2) { k -> cos(2.0 * Math.PI * k / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { k -> sin(2.0 * Math.PI * k / size).toFloat() }

    fun analyze(mono: FloatArray, spectrumDb: FloatArray) {
        require(mono.size >= size) { "need $size samples, got ${mono.size}" }
        require(spectrumDb.size >= binCount) { "need $binCount output bins, got ${spectrumDb.size}" }
        for (i in 0 until size) {
            real[i] = mono[i] * window[i]
            imaginary[i] = 0f
        }
        fft()
        val normalization = 4f / size
        for (k in 0 until binCount) {
            val magnitude = sqrt(real[k] * real[k] + imaginary[k] * imaginary[k]) * normalization
            spectrumDb[k] = 20f * log10(max(magnitude, MIN_MAGNITUDE))
        }
    }

    private fun fft() {
        bitReverse()
        var length = 2
        while (length <= size) {
            val half = length / 2
            val tableStep = size / length
            var blockStart = 0
            while (blockStart < size) {
                var tableIndex = 0
                for (offset in 0 until half) {
                    val twiddleReal = cosTable[tableIndex]
                    val twiddleImaginary = -sinTable[tableIndex]
                    val even = blockStart + offset
                    val odd = even + half
                    val productReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val productImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - productReal
                    imaginary[odd] = imaginary[even] - productImaginary
                    real[even] += productReal
                    imaginary[even] += productImaginary
                    tableIndex += tableStep
                }
                blockStart += length
            }
            length = length shl 1
        }
    }

    private fun bitReverse() {
        var reversed = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed or bit
            if (i < reversed) {
                swap(real, i, reversed)
                swap(imaginary, i, reversed)
            }
        }
    }

    private fun swap(values: FloatArray, a: Int, b: Int) {
        val saved = values[a]
        values[a] = values[b]
        values[b] = saved
    }

    companion object {
        const val DEFAULT_SIZE = 1024
        private const val MIN_MAGNITUDE = 1e-9f
    }
}
