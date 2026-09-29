package io.github.kickoman.qiyaa.queue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveModeRuleTest {
    @Test
    fun `a mode is off until the user turns it on`() {
        val rule = WaveModeRule()
        assertFalse(rule.wanted)
        assertFalse(rule.playerModeFor(isWave = false))
    }

    @Test
    fun `turning a mode on in a regular queue is remembered`() {
        val rule = WaveModeRule()
        assertTrue(rule.onPlayerChanged(enabled = true, isWave = false))
        assertTrue(rule.wanted)
        assertTrue(rule.playerModeFor(isWave = false))
    }

    @Test
    fun `a wave plays in server order but keeps the user's choice for later`() {
        val rule = WaveModeRule()
        rule.onPlayerChanged(enabled = true, isWave = false)
        assertFalse(rule.playerModeFor(isWave = true))
        assertTrue(rule.wanted)
        assertTrue(rule.playerModeFor(isWave = false))
    }

    @Test
    fun `turning a mode on during a wave is refused and not remembered`() {
        val rule = WaveModeRule()
        assertFalse(rule.onPlayerChanged(enabled = true, isWave = true))
        assertFalse(rule.wanted)
    }

    @Test
    fun `turning a mode off in a regular queue is remembered`() {
        val rule = WaveModeRule()
        rule.onPlayerChanged(enabled = true, isWave = false)
        assertFalse(rule.onPlayerChanged(enabled = false, isWave = false))
        assertFalse(rule.wanted)
    }
}
