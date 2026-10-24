package io.github.kickoman.qiyaa.ui

enum class Screen { LOGIN, PLAYER, PLAYLIST, EQ, LIBRARY, JAM, JAM_SETTINGS, JAM_GUEST, JAM_GUEST_ADD }

val Screen.backTarget: Screen?
    get() = when (this) {
        Screen.PLAYER -> null
        Screen.LOGIN -> Screen.LIBRARY
        Screen.PLAYLIST, Screen.EQ, Screen.LIBRARY, Screen.JAM, Screen.JAM_GUEST -> Screen.PLAYER
        Screen.JAM_SETTINGS -> Screen.JAM
        Screen.JAM_GUEST_ADD -> Screen.JAM_GUEST
    }

fun startScreen(signedIn: Boolean, restoreGuest: () -> Boolean): Screen = when {
    !signedIn -> Screen.LIBRARY
    restoreGuest() -> Screen.JAM_GUEST
    else -> Screen.PLAYER
}

fun screenAfterSignOut(expired: Boolean): Screen = if (expired) Screen.LOGIN else Screen.LIBRARY
