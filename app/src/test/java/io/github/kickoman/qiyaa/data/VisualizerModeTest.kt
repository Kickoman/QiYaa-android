package io.github.kickoman.qiyaa.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizerModeTest {
    @Test
    fun `fromStored maps the persisted integers and defaults to SPECTRUM`() {
        assertEquals(VisualizerMode.SPECTRUM, VisualizerMode.fromStored(0))
        assertEquals(VisualizerMode.SCOPE, VisualizerMode.fromStored(1))
        assertEquals(VisualizerMode.OFF, VisualizerMode.fromStored(2))
        assertEquals(VisualizerMode.SPECTRUM, VisualizerMode.fromStored(7))
    }

    @Test
    fun `next cycles spectrum to scope to off and back`() {
        assertEquals(VisualizerMode.SCOPE, VisualizerMode.SPECTRUM.next())
        assertEquals(VisualizerMode.OFF, VisualizerMode.SCOPE.next())
        assertEquals(VisualizerMode.SPECTRUM, VisualizerMode.OFF.next())
    }

    @Test
    fun `AccentTheme falls back to amber for unknown keys`() {
        assertEquals(AccentTheme.AMBER, AccentTheme.fromKey(null))
        assertEquals(AccentTheme.AMBER, AccentTheme.fromKey("purple"))
        assertEquals(AccentTheme.ICE_BLUE, AccentTheme.fromKey("ice"))
        assertEquals(AccentTheme.CLASSIC_GREEN, AccentTheme.ICE_BLUE.next())
    }
}
