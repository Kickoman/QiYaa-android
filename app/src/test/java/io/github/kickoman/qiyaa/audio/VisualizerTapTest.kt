package io.github.kickoman.qiyaa.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerTapTest {
    @Test
    fun `read returns the latest frames oldest first`() {
        val tap = VisualizerTap(8)
        tap.write(FloatArray(20) { (it / 2).toFloat() }, 10, 2)
        val left = FloatArray(4)
        val right = FloatArray(4)
        tap.read(left, right, 4)
        assertEquals(listOf(6f, 7f, 8f, 9f), left.toList())
        assertEquals(listOf(6f, 7f, 8f, 9f), right.toList())
    }

    @Test
    fun `mono input is copied to both channels`() {
        val tap = VisualizerTap(8)
        tap.write(floatArrayOf(1f, 2f, 3f), 3, 1)
        val left = FloatArray(3)
        val right = FloatArray(3)
        tap.read(left, right, 3)
        assertEquals(listOf(1f, 2f, 3f), left.toList())
        assertEquals(listOf(1f, 2f, 3f), right.toList())
    }

    @Test
    fun `read refuses more frames than the ring holds`() {
        val tap = VisualizerTap(8)
        try {
            tap.read(FloatArray(16), FloatArray(16), 16)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("16"))
        }
    }
}
