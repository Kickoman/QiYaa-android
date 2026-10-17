package io.github.kickoman.qiyaa.queue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveModeRuleTest {
    @Test
    fun `a mode is off until the user turns it on`() {
        val rule = WaveModeRule()
        assertFalse(rule.wanted)
        assertFalse(rule.playerModeFor(locked = false))
    }

    @Test
    fun `turning a mode on in a regular queue is remembered`() {
        val rule = WaveModeRule()
        assertTrue(rule.onUserChanged(enabled = true, locked = false))
        assertTrue(rule.wanted)
        assertTrue(rule.playerModeFor(locked = false))
    }

    @Test
    fun `a wave plays in server order but keeps the user's choice for later`() {
        val rule = WaveModeRule()
        rule.onUserChanged(enabled = true, locked = false)
        assertFalse(rule.playerModeFor(locked = true))
        assertTrue(rule.wanted)
        assertTrue(rule.playerModeFor(locked = false))
    }

    @Test
    fun `turning a mode on during a wave is refused and not remembered`() {
        val rule = WaveModeRule()
        assertFalse(rule.onUserChanged(enabled = true, locked = true))
        assertFalse(rule.wanted)
    }

    @Test
    fun `turning a mode off in a regular queue is remembered`() {
        val rule = WaveModeRule()
        rule.onUserChanged(enabled = true, locked = false)
        assertFalse(rule.onUserChanged(enabled = false, locked = false))
        assertFalse(rule.wanted)
    }
}
