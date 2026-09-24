package io.github.kickoman.qiyaa.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerTest {
    @Test
    fun presetsMatchWinamp() {
        assertEquals(17, EqPresets.builtin.size)
        assertEquals(-12.0, EqPresets.eqfToDb(1), 1e-9)
        assertEquals(12.0, EqPresets.eqfToDb(64), 1e-9)
        assertEquals("Classical", EqPresets.builtin.first().name)
        assertEquals("Techno", EqPresets.builtin.last().name)
        val rock = EqPresets.byName("Rock")!!
        assertEquals(EqPresets.eqfToDb(45), rock.settings.bandsDb[0], 1e-9)
        assertEquals(0.0, rock.settings.preampDb, 0.0)
        // A raw 33 is exactly flat, not eqfToDb(33) ≈ 0.19.
        assertEquals(0.0, EqPresets.byName("Classical")!!.settings.bandsDb[0], 0.0)
    }

    @Test
    fun flatIsIdentity() {
        val dsp = EqualizerDsp(44100)
        val buf = FloatArray(2048) { sin(it * 0.05).toFloat() }
        val copy = buf.copyOf()
        dsp.publish(EqSettings.FLAT)
        dsp.process(buf, 1024, 2)
        assertArrayEquals(copy, buf, 1e-6f)
    }

    @Test
    fun disabledPassesThrough() {
        val dsp = EqualizerDsp(44100)
        val s = EqSettings(enabled = false, preampDb = 6.0, bandsDb = DoubleArray(10) { 12.0 })
        dsp.publish(s)
        val buf = FloatArray(200) { 0.5f }
        dsp.process(buf, 100, 2)
        assertEquals(0.5f, buf[0], 0f)
        assertEquals(0.0, EqualizerDsp.responseDb(s, 1000.0, 44100.0), 0.0)
    }

    @Test
    fun responseAtBandCentreMatchesGain() {
        val s = EqSettings(bandsDb = DoubleArray(10).also { it[4] = 12.0 }) // 1 kHz
        assertEquals(12.0, EqualizerDsp.responseDb(s, 1000.0, 44100.0), 0.1)
        assertTrue(EqualizerDsp.responseDb(s, 60.0, 44100.0) < 0.5)
        val cut = EqSettings(bandsDb = DoubleArray(10).also { it[0] = -12.0 })
        assertEquals(-12.0, EqualizerDsp.responseDb(cut, 60.0, 44100.0), 0.1)
        assertEquals(6.0, EqualizerDsp.responseDb(EqSettings(preampDb = 6.0), 500.0, 44100.0), 1e-6)
    }

    @Test
    fun processedSineGainsAboutSixDb() {
        val dsp = EqualizerDsp(44100)
        dsp.publish(EqSettings(bandsDb = DoubleArray(10).also { it[4] = 6.0 }))
        val frames = 44100
        val buf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (0.5 * sin(2 * PI * 1000.0 * i / 44100)).toFloat()
            buf[2 * i] = v
            buf[2 * i + 1] = v
        }
        dsp.process(buf, frames, 2)
        // RMS over the last half second, after the filter settled.
        var sum = 0.0
        for (i in frames / 2 until frames) sum += (buf[2 * i] * buf[2 * i]).toDouble()
        val rms = sqrt(sum / (frames / 2))
        val gainDb = 20 * log10(rms / (0.5 / sqrt(2.0)))
        assertEquals(6.0, gainDb, 0.3)
    }

    @Test
    fun highBandsAreIdentityAtLowSampleRates() {
        val c = EqualizerDsp.compute(EqSettings(bandsDb = DoubleArray(10) { 12.0 }), 22050.0)
        assertTrue(c.bands[9].identity) // 16 kHz ≥ 0.49 × 22050
        assertTrue(!c.bands[0].identity)
    }
}
