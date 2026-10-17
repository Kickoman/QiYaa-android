package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.jam.GuestPhase
import io.github.kickoman.qiyaa.jam.JamLink

data class HostSide(
    val roomId: String? = null,
    val active: Boolean = false,
    val storedSession: Boolean = false,
)

data class GuestSide(
    val serverUrl: String? = null,
    val roomId: String? = null,
    val phase: GuestPhase = GuestPhase.None,
)

enum class LinkRefusal { NOT_A_LINK, HOSTING }

sealed interface LinkAction {
    data object Ignore : LinkAction

    data class Refuse(val reason: LinkRefusal) : LinkAction

    data class AskSignIn(val link: JamLink) : LinkAction

    data object OpenHostJam : LinkAction

    data object ShowGuest : LinkAction

    data class OpenGuest(val link: JamLink) : LinkAction
}

val GuestPhase.isSession: Boolean
    get() = isActive || this == GuestPhase.NeedName || this == GuestPhase.NoSecret

fun route(
    link: JamLink?,
    host: HostSide,
    guest: GuestSide,
    signedIn: Boolean,
    launchedFromHistory: Boolean,
): LinkAction = when {
    launchedFromHistory -> LinkAction.Ignore
    link == null -> LinkAction.Refuse(LinkRefusal.NOT_A_LINK)
    !signedIn -> LinkAction.AskSignIn(link)
    host.roomId != null && host.roomId == link.roomId -> LinkAction.OpenHostJam
    host.active || host.storedSession -> LinkAction.Refuse(LinkRefusal.HOSTING)
    guest.serverUrl == link.serverUrl &&
        guest.roomId == link.roomId &&
        (guest.phase.isActive || guest.phase == GuestPhase.NeedName) -> LinkAction.ShowGuest
    else -> LinkAction.OpenGuest(link)
}
