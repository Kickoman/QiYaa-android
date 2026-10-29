package io.github.kickoman.qiyaa.ui

enum class Screen {
    LOGIN,
    SPOTIFY_LOGIN,
    PLAYER,
    PLAYLIST,
    EQ,
    LIBRARY,
    JAM,
    JAM_SETTINGS,
    JAM_GUEST,
    JAM_GUEST_ADD,
    SEARCH,
}

val Screen.backTarget: Screen?
    get() = when (this) {
        Screen.PLAYER -> null
        Screen.LOGIN, Screen.SPOTIFY_LOGIN, Screen.SEARCH -> Screen.LIBRARY
        Screen.PLAYLIST, Screen.EQ, Screen.LIBRARY, Screen.JAM, Screen.JAM_GUEST -> Screen.PLAYER
        Screen.JAM_SETTINGS -> Screen.JAM
        Screen.JAM_GUEST_ADD -> Screen.JAM_GUEST
    }

val Screen.isLogin: Boolean
    get() = this == Screen.LOGIN || this == Screen.SPOTIFY_LOGIN

fun startScreen(signedIn: Boolean, spotifyLoggedIn: Boolean, restoreGuest: () -> Boolean): Screen = when {
    signedIn && restoreGuest() -> Screen.JAM_GUEST
    signedIn || spotifyLoggedIn -> Screen.PLAYER
    else -> Screen.LIBRARY
}

fun screenAfterSignOut(expired: Boolean): Screen = if (expired) Screen.LOGIN else Screen.LIBRARY
