package io.github.kickoman.qiyaa.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Port of src/audio/Equalizer.h EqSettings. */
data class EqSettings(
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    val bandsDb: DoubleArray = DoubleArray(BANDS),
) {
    val isFlat: Boolean get() = preampDb == 0.0 && bandsDb.all { it == 0.0 }

    override fun equals(other: Any?): Boolean =
        other is EqSettings && enabled == other.enabled && preampDb == other.preampDb && bandsDb.contentEquals(other.bandsDb)

    override fun hashCode(): Int = 31 * (31 * enabled.hashCode() + preampDb.hashCode()) + bandsDb.contentHashCode()

    companion object {
        const val BANDS = 10
        val BAND_HZ = doubleArrayOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)
        val BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")
        const val MAX_DB = 12.0
        val FLAT = EqSettings()
    }
}

/**
 * Port of src/audio/Equalizer.cpp: ten RBJ peaking biquads (Q = 1.2) plus a preamp,
 * processed in place on interleaved float frames. Coefficients are published from any
 * thread as an immutable object and picked up by the audio thread on the next block.
 */
class EqualizerDsp(sampleRate: Int = 44100) {
    class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float, val identity: Boolean) {
        companion object {
            val IDENTITY = Biquad(1f, 0f, 0f, 0f, 0f, true)
        }
    }

    class Coeffs(val enabled: Boolean, val preamp: Float, val bands: Array<Biquad>)

    @Volatile
    private var coeffs: Coeffs = compute(EqSettings.FLAT, sampleRate.toDouble())

    @Volatile
    var sampleRate: Int = sampleRate
        private set

    private var last: EqSettings = EqSettings.FLAT

    // Filter state: [band][channel]
    private val z1 = Array(EqSettings.BANDS) { FloatArray(MAX_CHANNELS) }
    private val z2 = Array(EqSettings.BANDS) { FloatArray(MAX_CHANNELS) }

    fun setSampleRate(rate: Int) {
        sampleRate = if (rate > 0) rate else 44100
        coeffs = compute(last, sampleRate.toDouble())
    }

    fun publish(settings: EqSettings) {
        last = settings
        coeffs = compute(settings, sampleRate.toDouble())
    }

    fun reset() {
        for (b in 0 until EqSettings.BANDS) {
            z1[b].fill(0f)
            z2[b].fill(0f)
        }
    }

    /** Processes [frameCount] interleaved frames of [channels] channels in place. */
    fun process(frames: FloatArray, frameCount: Int, channels: Int) {
        val c = coeffs
        if (!c.enabled) return
        val ch = channels.coerceIn(1, MAX_CHANNELS)
        val n = frameCount * channels

        if (c.preamp != 1f) for (i in 0 until n) frames[i] *= c.preamp

        for (band in 0 until EqSettings.BANDS) {
            val b = c.bands[band]
            if (b.identity) {
                // Let the state decay so re-enabling a band doesn't pop.
                z1[band].fill(0f)
                z2[band].fill(0f)
                continue
            }
            for (chan in 0 until ch) {
                var s1 = z1[band][chan]
                var s2 = z2[band][chan]
                var p = chan
                for (i in 0 until frameCount) {
                    // Transposed direct form II.
                    val x = frames[p]
                    val y = b.b0 * x + s1
                    s1 = b.b1 * x - b.a1 * y + s2
                    s2 = b.b2 * x - b.a2 * y
                    frames[p] = y
                    p += channels
                }
                // Flush denormals.
                z1[band][chan] = if (abs(s1) < 1e-15f) 0f else s1
                z2[band][chan] = if (abs(s2) < 1e-15f) 0f else s2
            }
        }
    }

    companion object {
        const val Q = 1.2
        const val MAX_CHANNELS = 2

        fun compute(s: EqSettings, sampleRate: Double): Coeffs {
            val preamp = 10.0.pow(s.preampDb.coerceIn(-EqSettings.MAX_DB, EqSettings.MAX_DB) / 20.0).toFloat()
            val bands = Array(EqSettings.BANDS) { i ->
                val db = s.bandsDb[i].coerceIn(-EqSettings.MAX_DB, EqSettings.MAX_DB)
                val f0 = EqSettings.BAND_HZ[i]
                if (abs(db) < 0.05 || f0 >= sampleRate * 0.49) return@Array Biquad.IDENTITY
                // RBJ audio EQ cookbook, peaking EQ.
                val a = 10.0.pow(db / 40.0)
                val w0 = 2.0 * Math.PI * f0 / sampleRate
                val alpha = sin(w0) / (2.0 * Q)
                val cw = cos(w0)
                val a0 = 1.0 + alpha / a
                Biquad(
                    b0 = ((1.0 + alpha * a) / a0).toFloat(),
                    b1 = ((-2.0 * cw) / a0).toFloat(),
                    b2 = ((1.0 - alpha * a) / a0).toFloat(),
                    a1 = ((-2.0 * cw) / a0).toFloat(),
                    a2 = ((1.0 - alpha / a) / a0).toFloat(),
                    identity = false,
                )
            }
            return Coeffs(s.enabled, preamp, bands)
        }

        /** Magnitude response of the whole cascade at [hz], in dB (used by tests and the curve). */
        fun responseDb(s: EqSettings, hz: Double, sampleRate: Double): Double {
            val c = compute(s, sampleRate)
            if (!c.enabled) return 0.0
            val w = -2.0 * Math.PI * hz / sampleRate
            // z^-1 = e^{-jw}
            var re = c.preamp.toDouble()
            var im = 0.0
            val zr = cos(w)
            val zi = sin(w)
            val z2r = zr * zr - zi * zi
            val z2i = 2 * zr * zi
            for (b in c.bands) {
                if (b.identity) continue
                val nr = b.b0 + b.b1 * zr + b.b2 * z2r
                val ni = b.b1 * zi + b.b2 * z2i
                val dr = 1.0 + b.a1 * zr + b.a2 * z2r
                val di = b.a1 * zi + b.a2 * z2i
                // (nr + j ni) / (dr + j di)
                val den = dr * dr + di * di
                val qr = (nr * dr + ni * di) / den
                val qi = (ni * dr - nr * di) / den
                val r = re * qr - im * qi
                val i = re * qi + im * qr
                re = r
                im = i
            }
            return 20.0 * log10(Math.hypot(re, im))
        }
    }
}
