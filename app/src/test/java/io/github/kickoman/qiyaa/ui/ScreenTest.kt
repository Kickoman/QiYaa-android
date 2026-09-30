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
    fun `Back from the jam goes to the player, and from the jam server settings to the jam`() {
        assertEquals(Screen.PLAYER, Screen.JAM.backTarget)
        assertEquals(Screen.JAM, Screen.JAM_SETTINGS.backTarget)
    }

    @Test
    fun `Back from the player or the sign-in screen is left to the system and closes the app`() {
        assertEquals(null, Screen.PLAYER.backTarget)
        assertEquals(null, Screen.LOGIN.backTarget)
    }
}
