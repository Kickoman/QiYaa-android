package io.github.kickoman.qiyaa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationTest {
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
    fun `Back from the guest's room goes to the player, and from adding tracks to the room`() {
        assertEquals(Screen.PLAYER, Screen.JAM_GUEST.backTarget)
        assertEquals(Screen.JAM_GUEST, Screen.JAM_GUEST_ADD.backTarget)
    }

    @Test
    fun `Back from the player is left to the system and closes the app`() {
        assertEquals(null, Screen.PLAYER.backTarget)
    }

    @Test
    fun `Back from the sign-in screen goes to the library, so the sign-in can be left`() {
        assertEquals(Screen.LIBRARY, Screen.LOGIN.backTarget)
    }

    @Test
    fun `Without a sign-in the app starts on the library and never restores the guest's session`() {
        var restored = false
        assertEquals(
            Screen.LIBRARY,
            startScreen(signedIn = false) {
                restored = true
                true
            },
        )
        assertFalse(restored)
    }

    @Test
    fun `Signed in, the app starts in the guest's room when its session comes back, else on the player`() {
        assertEquals(Screen.JAM_GUEST, startScreen(signedIn = true) { true })
        assertEquals(Screen.PLAYER, startScreen(signedIn = true) { false })
    }

    @Test
    fun `Signing out leads to the library, an expired token to the sign-in screen`() {
        assertEquals(Screen.LIBRARY, screenAfterSignOut(expired = false))
        assertEquals(Screen.LOGIN, screenAfterSignOut(expired = true))
    }
}
