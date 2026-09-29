package io.github.kickoman.qiyaa.audio

import io.github.kickoman.qiyaa.support.Spec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Vectors from spec/dsp (written by the desktop app). eqf.json's dbToLevel, parsedBytes and the
// file layout are left out: .eqf import and export are n/a on Android (spec/parity.md).
class DspVectorsTest {
    @Test
    fun `the band centres and Q match the spec`() {
        val vectors = Spec.dsp("eq-response")
        assertEquals(vectors.doubles("bandsHz"), EqSettings.BAND_HZ)
        assertEquals(vectors.getValue("q").jsonPrimitive.double, EqualizerDsp.Q, 0.0)
    }

    @Test
    fun `the equalizer response matches the spec for every case, rate and frequency`() {
        val vectors = Spec.dsp("eq-response")
        val tolerance = vectors.tolerance("db")
        val frequencies = vectors.doubles("frequenciesHz")
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(50, cases.size)
        for (case in cases) {
            val settings = case.getValue("settings").jsonObject
            val eq =
                EqSettings(
                    enabled = settings.getValue("enabled").jsonPrimitive.boolean,
                    preampDb = settings.getValue("preampDb").jsonPrimitive.double,
                    bandsDb = settings.doubles("bandsDb"),
                )
            val sampleRate = case.getValue("sampleRate").jsonPrimitive.double
            val expected = case.doubles("responseDb")
            val name = case.getValue("name").jsonPrimitive.content
            frequencies.forEachIndexed { index, hz ->
                val actual = EqualizerDsp.responseDb(eq, hz, sampleRate)
                assertEquals("$name @ ${sampleRate.toInt()} Hz, $hz Hz", expected[index], actual, tolerance)
            }
        }
    }

    @Test
    fun `the built-in presets match the spec in order, levels and dB`() {
        val vectors = Spec.dsp("eq-presets")
        val tolerance = vectors.tolerance("db")
        val presets = vectors.getValue("presets").jsonArray.map { it.jsonObject }
        assertEquals(
            presets.map {
                it.getValue("name").jsonPrimitive.content
            },
            EqPresets.builtin.map { it.name },
        )
        for ((expected, preset) in presets.zip(EqPresets.builtin)) {
            assertEquals(preset.name, expected.ints("levels"), preset.eqfLevels)
            assertEquals(
                preset.name,
                EqPresets.levelToDb(expected.getValue("preampLevel").jsonPrimitive.int),
                preset.settings.preampDb,
                tolerance,
            )
            assertEquals(
                preset.name,
                expected.getValue("preampDb").jsonPrimitive.double,
                preset.settings.preampDb,
                tolerance,
            )
            expected.doubles("bandsDb").forEachIndexed { band, db ->
                assertEquals("${preset.name} band $band", db, preset.settings.bandsDb[band], tolerance)
            }
        }
    }

    @Test
    fun `eqf levels map to dB as in the spec, clamped to 1 to 64 with 33 at exactly 0`() {
        val vectors = Spec.dsp("eqf")
        val tolerance = vectors.tolerance("db")
        val pairs = vectors.getValue("levelToDb").jsonArray.map { it.jsonObject }
        assertEquals(66, pairs.size)
        for (pair in pairs) {
            val level = pair.getValue("level").jsonPrimitive.int
            assertEquals(
                "level $level",
                pair.getValue("db").jsonPrimitive.double,
                EqPresets.levelToDb(level),
                tolerance,
            )
        }
    }

    @Test
    fun `the spectrum bands have the spec's edges and bins`() {
        val vectors = Spec.dsp("spectrum")
        val fftSize = vectors.getValue("fftSize").jsonPrimitive.int
        for ((rate, bands) in vectors.getValue("bands").jsonObject) {
            val actual = Spectrum.bands(fftSize, rate.toInt())
            val expected = bands.jsonArray.map { it.jsonObject }
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEachIndexed { index, (want, got) ->
                val where = "$rate Hz band $index"
                assertEquals(where, want.getValue("firstBin").jsonPrimitive.int, got.firstBin)
                assertEquals(where, want.getValue("endBin").jsonPrimitive.int, got.endBin)
                assertEquals(where, want.getValue("lowHz").jsonPrimitive.double, got.lowHz, 1e-9)
                assertEquals(where, want.getValue("highHz").jsonPrimitive.double, got.highHz, 1e-9)
            }
        }
    }

    @Test
    fun `one frame of the spectrum matches the spec's levels for every sine`() {
        val vectors = Spec.dsp("spectrum")
        val fftSize = vectors.getValue("fftSize").jsonPrimitive.int
        val tolerance = vectors.tolerance("level")
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val sampleRate = case.getValue("sampleRate").jsonPrimitive.int
            val sine = case.getValue("sine").jsonObject
            val amplitude = sine.getValue("amplitude").jsonPrimitive.double
            val hz = sine.getValue("hz").jsonPrimitive.double
            val samples =
                FloatArray(fftSize) { i -> (amplitude * sin(2 * PI * hz * i / sampleRate)).toFloat() }
            val analyzer = Analyzer(fftSize)
            val spectrumDb = FloatArray(analyzer.binCount)
            analyzer.analyze(samples, spectrumDb)
            val spectrum = Spectrum()
            spectrum.update(spectrumDb, fftSize, sampleRate)
            val expected = case.doubles("levels")
            expected.forEachIndexed { bar, level ->
                val actual = spectrum.levels[bar].toDouble()
                assertTrue(
                    "$sampleRate Hz, $amplitude × $hz Hz, bar $bar: $level vs $actual",
                    abs(level - actual) <= tolerance,
                )
            }
        }
    }

    private fun JsonObject.doubles(key: String): List<Double> = getValue(key).jsonArray.map {
        it.jsonPrimitive.double
    }

    private fun JsonObject.ints(key: String): List<Int> = getValue(key).jsonArray.map { it.jsonPrimitive.int }

    private fun JsonObject.tolerance(key: String): Double =
        getValue("tolerance").jsonObject.getValue(key).jsonPrimitive.double
}
