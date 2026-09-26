package io.github.kickoman.qiyaa.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerTest {
    private val sampleRate = 44100

    @Test
    fun `the 17 presets match Winamp's builtin table`() {
        assertEquals(17, EqPresets.builtin.size)
        assertEquals(-12.0, EqPresets.eqfToDb(1), 1e-9)
        assertEquals(12.0, EqPresets.eqfToDb(64), 1e-9)
        assertEquals("Classical", EqPresets.builtin.first().name)
        assertEquals("Techno", EqPresets.builtin.last().name)
        val rock = EqPresets.byName("Rock")!!
        assertEquals(EqPresets.eqfToDb(45), rock.settings.bandsDb[0], 1e-9)
        assertEquals(0.0, rock.settings.preampDb, 0.0)
    }

    @Test
    fun `an eqf level of 33 is exactly flat`() {
        assertEquals(0.0, EqPresets.byName("Classical")!!.settings.bandsDb[0], 0.0)
    }

    @Test
    fun `flat settings leave the signal untouched`() {
        val equalizer = EqualizerDsp(sampleRate)
        val buffer = FloatArray(2048) { sin(it * 0.05).toFloat() }
        val copy = buffer.copyOf()
        equalizer.publish(EqSettings.FLAT)
        equalizer.process(buffer, 1024, 2)
        assertArrayEquals(copy, buffer, 1e-6f)
    }

    @Test
    fun `a disabled equalizer passes the signal through`() {
        val equalizer = EqualizerDsp(sampleRate)
        val settings = EqSettings(enabled = false, preampDb = 6.0, bandsDb = List(10) { 12.0 })
        equalizer.publish(settings)
        val buffer = FloatArray(200) { 0.5f }
        equalizer.process(buffer, 100, 2)
        assertEquals(0.5f, buffer[0], 0f)
        assertEquals(0.0, EqualizerDsp.responseDb(settings, 1000.0, sampleRate.toDouble()), 0.0)
    }

    @Test
    fun `the response at a band centre equals the band gain`() {
        val boost = EqSettings().withBand(4, 12.0)
        assertEquals(12.0, EqualizerDsp.responseDb(boost, 1000.0, sampleRate.toDouble()), 0.1)
        assertTrue(EqualizerDsp.responseDb(boost, 60.0, sampleRate.toDouble()) < 0.5)
        val cut = EqSettings().withBand(0, -12.0)
        assertEquals(-12.0, EqualizerDsp.responseDb(cut, 60.0, sampleRate.toDouble()), 0.1)
        assertEquals(
            6.0,
            EqualizerDsp.responseDb(EqSettings(preampDb = 6.0), 500.0, sampleRate.toDouble()),
            1e-6,
        )
    }

    @Test
    fun `a processed 1 kHz sine gains about 6 dB with the 1 kHz band at plus 6`() {
        val equalizer = EqualizerDsp(sampleRate)
        equalizer.publish(EqSettings().withBand(4, 6.0))
        val frames = sampleRate
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val value = (0.5 * sin(2 * PI * 1000.0 * i / sampleRate)).toFloat()
            buffer[2 * i] = value
            buffer[2 * i + 1] = value
        }
        equalizer.process(buffer, frames, 2)
        var sum = 0.0
        for (i in frames / 2 until frames) sum += (buffer[2 * i] * buffer[2 * i]).toDouble()
        val rms = sqrt(sum / (frames / 2))
        val gainDb = 20 * log10(rms / (0.5 / sqrt(2.0)))
        assertEquals(6.0, gainDb, 0.3)
    }

    @Test
    fun `bands above half the sample rate become identity`() {
        val coefficients = EqualizerDsp.compute(EqSettings(bandsDb = List(10) { 12.0 }), 22050.0)
        assertTrue(coefficients.bands[9].identity)
        assertFalse(coefficients.bands[0].identity)
    }

    @Test
    fun `withBand clamps to plus or minus 12 dB`() {
        assertEquals(12.0, EqSettings().withBand(2, 40.0).bandsDb[2], 0.0)
        assertEquals(-12.0, EqSettings().withBand(2, -40.0).bandsDb[2], 0.0)
    }

    @Test
    fun `EqSettings refuses the wrong number of bands`() {
        try {
            EqSettings(bandsDb = List(9) { 0.0 })
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("9"))
        }
    }
}
