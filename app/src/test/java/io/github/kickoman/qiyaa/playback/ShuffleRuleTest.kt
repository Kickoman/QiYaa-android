package io.github.kickoman.qiyaa.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleRuleTest {
    @Test
    fun `shuffle is off until the user turns it on`() {
        val rule = ShuffleRule()
        assertFalse(rule.wanted)
        assertFalse(rule.playerModeFor(isWave = false))
    }

    @Test
    fun `turning shuffle on in a regular queue is remembered`() {
        val rule = ShuffleRule()
        assertTrue(rule.onPlayerChanged(enabled = true, isWave = false))
        assertTrue(rule.wanted)
        assertTrue(rule.playerModeFor(isWave = false))
    }

    @Test
    fun `a wave plays in server order but keeps the user's choice for later`() {
        val rule = ShuffleRule()
        rule.onPlayerChanged(enabled = true, isWave = false)
        assertFalse(rule.playerModeFor(isWave = true))
        assertTrue(rule.wanted)
        assertTrue(rule.playerModeFor(isWave = false))
    }

    @Test
    fun `turning shuffle on during a wave is refused and not remembered`() {
        val rule = ShuffleRule()
        assertFalse(rule.onPlayerChanged(enabled = true, isWave = true))
        assertFalse(rule.wanted)
    }

    @Test
    fun `turning shuffle off in a regular queue is remembered`() {
        val rule = ShuffleRule()
        rule.onPlayerChanged(enabled = true, isWave = false)
        assertFalse(rule.onPlayerChanged(enabled = false, isWave = false))
        assertFalse(rule.wanted)
    }
}
