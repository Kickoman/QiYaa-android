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
    fun `Back from the sign-in screen is the app's, so the sign-in can be left`() {
        assertEquals(Screen.LIBRARY, Screen.LOGIN.backTarget)
    }

    @Test
    fun `Back from the Spotify login goes to the library it was opened from`() {
        assertEquals(Screen.LIBRARY, Screen.SPOTIFY_LOGIN.backTarget)
    }

    @Test
    fun `Both logins are login screens, without tabs and the mini player`() {
        assertEquals(setOf(Screen.LOGIN, Screen.SPOTIFY_LOGIN), Screen.entries.filter { it.isLogin }.toSet())
    }

    @Test
    fun `Without a sign-in the app starts on the library and never restores the guest's session`() {
        var restored = false
        assertEquals(
            Screen.LIBRARY,
            startScreen(signedIn = false, spotifyLoggedIn = false) {
                restored = true
                true
            },
        )
        assertFalse(restored)
    }

    @Test
    fun `Signed in, the app starts in the guest's room when its session comes back, else on the player`() {
        assertEquals(Screen.JAM_GUEST, startScreen(signedIn = true, spotifyLoggedIn = false) { true })
        assertEquals(Screen.PLAYER, startScreen(signedIn = true, spotifyLoggedIn = false) { false })
    }

    @Test
    fun `SPOT-04 a saved Spotify login alone starts on the player and never restores the guest's session`() {
        var restored = false
        val screen =
            startScreen(signedIn = false, spotifyLoggedIn = true) {
                restored = true
                true
            }
        assertEquals(Screen.PLAYER, screen)
        assertFalse(restored)
        assertEquals(Screen.JAM_GUEST, startScreen(signedIn = true, spotifyLoggedIn = true) { true })
        assertEquals(Screen.PLAYER, startScreen(signedIn = true, spotifyLoggedIn = true) { false })
    }

    @Test
    fun `the four Spotify sections open Spotify's lists, and no other section does`() {
        val lists = LibrarySection.entries.associateWith { it.spotifyList }.filterValues { it != null }
        val expected =
            mapOf(
                LibrarySection.SPOTIFY_FOR_YOU to SpotifyList.FOR_YOU,
                LibrarySection.SPOTIFY_PLAYLISTS to SpotifyList.PLAYLISTS,
                LibrarySection.SPOTIFY_ALBUMS to SpotifyList.ALBUMS,
                LibrarySection.SPOTIFY_ARTISTS to SpotifyList.ARTISTS,
            )
        assertEquals(expected, lists)
        assertFalse(LibrarySection.entries.any { it.isLocal && it.spotifyList != null })
    }

    @Test
    fun `Signing out leads to the library, an expired token to the sign-in screen`() {
        assertEquals(Screen.LIBRARY, screenAfterSignOut(expired = false))
        assertEquals(Screen.LOGIN, screenAfterSignOut(expired = true))
    }
}
