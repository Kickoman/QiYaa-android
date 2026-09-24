package io.github.kickoman.qiyaa.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AnalyzerTest {
    @Test
    fun fullScaleSineReadsZeroDbfsAtItsBin() {
        val n = 1024
        val sr = 44100
        val bin = 23 // ≈ 990 Hz
        val f = bin * sr / n.toDouble()
        val mono = FloatArray(n) { sin(2 * PI * f * it / sr).toFloat() }
        val db = FloatArray(n / 2 + 1)
        Analyzer(n).analyze(mono, db)
        val peak = db.indices.maxByOrNull { db[it] }!!
        assertEquals(bin, peak)
        assertEquals(0f, db[bin], 0.5f)
        assertTrue(db[200] < -60f)
    }

    @Test
    fun spectrumLightsTheMatchingBar() {
        val n = 1024
        val sr = 44100
        val mono = FloatArray(n) { sin(2 * PI * 1000.0 * it / sr).toFloat() }
        val db = FloatArray(n / 2 + 1)
        Analyzer(n).analyze(mono, db)
        val s = Spectrum(19)
        s.update(db, n, sr)
        val hot = s.levels.indices.maxByOrNull { s.levels[it] }!!
        // 1 kHz sits about halfway between 60 Hz and 16 kHz on a log scale.
        assertTrue("bar $hot", hot in 8..11)
        assertEquals(1f, s.levels[hot], 0.05f)
        // Silence lets the bar fall by 0.07 per frame and the peak hold behind it.
        s.update(FloatArray(n / 2 + 1) { -100f }, n, sr)
        assertEquals(0.93f, s.levels[hot], 0.05f)
        assertTrue(s.peaks[hot] >= s.levels[hot])
    }

    @Test
    fun visTapReturnsLatestFramesOldestFirst() {
        val tap = VisTap(8)
        tap.write(FloatArray(20) { (it / 2).toFloat() }, 10, 2)
        val l = FloatArray(4)
        val r = FloatArray(4)
        tap.read(l, r, 4)
        assertEquals(listOf(6f, 7f, 8f, 9f), l.toList())
        assertEquals(listOf(6f, 7f, 8f, 9f), r.toList())
    }
}
