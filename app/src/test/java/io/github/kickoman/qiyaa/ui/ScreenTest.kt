package io.github.kickoman.qiyaa.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun `Back from the EQ, playlist and library tabs goes to the player`() {
        assertEquals(Screen.PLAYER, Screen.EQ.backTarget)
        assertEquals(Screen.PLAYER, Screen.PLAYLIST.backTarget)
        assertEquals(Screen.PLAYER, Screen.LIBRARY.backTarget)
    }

    @Test
    fun `Back from the player or the sign-in screen is left to the system and closes the app`() {
        assertEquals(null, Screen.PLAYER.backTarget)
        assertEquals(null, Screen.LOGIN.backTarget)
    }
}
