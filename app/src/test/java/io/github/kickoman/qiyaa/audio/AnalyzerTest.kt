package io.github.kickoman.qiyaa.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerTest {
    private val size = 1024
    private val sampleRate = 44100

    @Test
    fun `a full-scale sine reads 0 dBFS at its bin`() {
        val bin = 23
        val hz = bin * sampleRate / size.toDouble()
        val mono = FloatArray(size) { sin(2 * PI * hz * it / sampleRate).toFloat() }
        val analyzer = Analyzer(size)
        val spectrumDb = FloatArray(analyzer.binCount)
        analyzer.analyze(mono, spectrumDb)
        val peak = spectrumDb.indices.maxByOrNull { spectrumDb[it] }!!
        assertEquals(bin, peak)
        assertEquals(0f, spectrumDb[bin], 0.5f)
        assertTrue(spectrumDb[200] < -60f)
    }

    @Test
    fun `Spectrum lights the bar that contains 1 kHz and lets it fall by 0_07 per frame`() {
        val mono = FloatArray(size) { sin(2 * PI * 1000.0 * it / sampleRate).toFloat() }
        val analyzer = Analyzer(size)
        val spectrumDb = FloatArray(analyzer.binCount)
        analyzer.analyze(mono, spectrumDb)
        val spectrum = Spectrum(19)
        spectrum.update(spectrumDb, size, sampleRate)
        val hot = spectrum.levels.indices.maxByOrNull { spectrum.levels[it] }!!
        assertTrue("bar $hot", hot in 8..11)
        assertEquals(1f, spectrum.levels[hot], 0.05f)
        spectrum.update(FloatArray(analyzer.binCount) { -100f }, size, sampleRate)
        assertEquals(1f - Spectrum.LEVEL_FALL_PER_FRAME, spectrum.levels[hot], 0.05f)
        assertTrue(spectrum.peaks[hot] >= spectrum.levels[hot])
    }

    @Test
    fun `Analyzer rejects sizes that are not a power of two`() {
        try {
            Analyzer(1000)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("1000"))
        }
    }
}
