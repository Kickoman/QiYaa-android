package io.github.kickoman.qiyaa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityTest {
    @Test
    fun `QUAL-06 the stored names read back and anything else is standard`() {
        assertEquals(AudioQuality.ECONOMY, AudioQuality.fromKey("economy"))
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromKey("standard"))
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromKey("lossless"))
        assertEquals(AudioQuality.STANDARD, AudioQuality.fromKey(null))
    }
}
