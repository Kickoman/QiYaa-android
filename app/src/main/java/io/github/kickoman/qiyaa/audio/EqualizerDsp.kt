package io.github.kickoman.qiyaa.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

data class EqSettings(
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    val bandsDb: List<Double> = List(BAND_COUNT) { 0.0 },
) {
    init {
        require(bandsDb.size == BAND_COUNT) { "expected $BAND_COUNT bands, got ${bandsDb.size}" }
    }

    val isFlat: Boolean get() = preampDb == 0.0 && bandsDb.all { it == 0.0 }

    fun withBand(index: Int, db: Double): EqSettings =
        copy(bandsDb = bandsDb.mapIndexed { i, old -> if (i == index) db.coerceIn(-MAX_DB, MAX_DB) else old })

    companion object {
        const val BAND_COUNT = 10
        const val MAX_DB = 12.0
        val BAND_HZ = listOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)
        val FLAT = EqSettings()
    }
}

class EqualizerDsp(sampleRate: Int = DEFAULT_SAMPLE_RATE) {
    class Biquad(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float,
        val identity: Boolean,
    ) {
        companion object {
            val IDENTITY = Biquad(1f, 0f, 0f, 0f, 0f, true)
        }
    }

    class Coefficients(val enabled: Boolean, val preamp: Float, val bands: List<Biquad>)

    @Volatile
    private var coefficients: Coefficients = compute(EqSettings.FLAT, sampleRate.toDouble())

    @Volatile
    var sampleRate: Int = sampleRate
        private set

    private var lastSettings: EqSettings = EqSettings.FLAT

    private val delay1 = Array(EqSettings.BAND_COUNT) { FloatArray(MAX_CHANNELS) }
    private val delay2 = Array(EqSettings.BAND_COUNT) { FloatArray(MAX_CHANNELS) }

    fun setSampleRate(rate: Int) {
        sampleRate = if (rate > 0) rate else DEFAULT_SAMPLE_RATE
        coefficients = compute(lastSettings, sampleRate.toDouble())
    }

    fun publish(settings: EqSettings) {
        lastSettings = settings
        coefficients = compute(settings, sampleRate.toDouble())
    }

    fun reset() {
        for (band in 0 until EqSettings.BAND_COUNT) {
            delay1[band].fill(0f)
            delay2[band].fill(0f)
        }
    }

    fun process(frames: FloatArray, frameCount: Int, channels: Int) {
        val current = coefficients
        if (!current.enabled) return
        val channelCount = channels.coerceIn(1, MAX_CHANNELS)
        val sampleCount = frameCount * channels

        if (current.preamp != 1f) for (i in 0 until sampleCount) frames[i] *= current.preamp

        for (band in 0 until EqSettings.BAND_COUNT) {
            val biquad = current.bands[band]
            if (biquad.identity) {
                delay1[band].fill(0f)
                delay2[band].fill(0f)
                continue
            }
            for (channel in 0 until channelCount) {
                var state1 = delay1[band][channel]
                var state2 = delay2[band][channel]
                var position = channel
                for (i in 0 until frameCount) {
                    val x = frames[position]
                    val y = biquad.b0 * x + state1
                    state1 = biquad.b1 * x - biquad.a1 * y + state2
                    state2 = biquad.b2 * x - biquad.a2 * y
                    frames[position] = y
                    position += channels
                }
                delay1[band][channel] = if (abs(state1) < DENORMAL_THRESHOLD) 0f else state1
                delay2[band][channel] = if (abs(state2) < DENORMAL_THRESHOLD) 0f else state2
            }
        }
    }

    companion object {
        const val Q = 1.2
        const val MAX_CHANNELS = 2
        const val DEFAULT_SAMPLE_RATE = 44_100
        private const val DENORMAL_THRESHOLD = 1e-15f
        private const val IDENTITY_BELOW_DB = 0.05
        private const val NYQUIST_MARGIN = 0.49

        fun compute(settings: EqSettings, sampleRate: Double): Coefficients {
            val preampDb = settings.preampDb.coerceIn(-EqSettings.MAX_DB, EqSettings.MAX_DB)
            val preamp = 10.0.pow(preampDb / 20.0).toFloat()
            val bands =
                List(EqSettings.BAND_COUNT) { index ->
                    val db = settings.bandsDb[index].coerceIn(-EqSettings.MAX_DB, EqSettings.MAX_DB)
                    val centerHz = EqSettings.BAND_HZ[index]
                    if (abs(db) < IDENTITY_BELOW_DB || centerHz >= sampleRate * NYQUIST_MARGIN) {
                        Biquad.IDENTITY
                    } else {
                        peakingBiquad(db, centerHz, sampleRate)
                    }
                }
            return Coefficients(settings.enabled, preamp, bands)
        }

        // RBJ Audio EQ Cookbook, peaking EQ; the a0-normalised coefficients keep the cookbook names.
        private fun peakingBiquad(db: Double, centerHz: Double, sampleRate: Double): Biquad {
            val amplitude = 10.0.pow(db / 40.0)
            val omega = 2.0 * Math.PI * centerHz / sampleRate
            val alpha = sin(omega) / (2.0 * Q)
            val cosOmega = cos(omega)
            val a0 = 1.0 + alpha / amplitude
            return Biquad(
                b0 = ((1.0 + alpha * amplitude) / a0).toFloat(),
                b1 = ((-2.0 * cosOmega) / a0).toFloat(),
                b2 = ((1.0 - alpha * amplitude) / a0).toFloat(),
                a1 = ((-2.0 * cosOmega) / a0).toFloat(),
                a2 = ((1.0 - alpha / amplitude) / a0).toFloat(),
                identity = false,
            )
        }

        fun responseDb(settings: EqSettings, hz: Double, sampleRate: Double): Double {
            val current = compute(settings, sampleRate)
            if (!current.enabled) return 0.0
            val omega = -2.0 * Math.PI * hz / sampleRate
            val zReal = cos(omega)
            val zImaginary = sin(omega)
            val z2Real = zReal * zReal - zImaginary * zImaginary
            val z2Imaginary = 2 * zReal * zImaginary
            var real = current.preamp.toDouble()
            var imaginary = 0.0
            for (biquad in current.bands) {
                if (biquad.identity) continue
                val numeratorReal = biquad.b0 + biquad.b1 * zReal + biquad.b2 * z2Real
                val numeratorImaginary = biquad.b1 * zImaginary + biquad.b2 * z2Imaginary
                val denominatorReal = 1.0 + biquad.a1 * zReal + biquad.a2 * z2Real
                val denominatorImaginary = biquad.a1 * zImaginary + biquad.a2 * z2Imaginary
                val denominator =
                    denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
                val quotientReal =
                    (numeratorReal * denominatorReal + numeratorImaginary * denominatorImaginary) /
                        denominator
                val quotientImaginary =
                    (numeratorImaginary * denominatorReal - numeratorReal * denominatorImaginary) /
                        denominator
                val productReal = real * quotientReal - imaginary * quotientImaginary
                val productImaginary = real * quotientImaginary + imaginary * quotientReal
                real = productReal
                imaginary = productImaginary
            }
            return 20.0 * log10(hypot(real, imaginary))
        }
    }
}
