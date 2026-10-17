package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.jam.EndReason
import io.github.kickoman.qiyaa.jam.GuestPhase
import io.github.kickoman.qiyaa.jam.JamLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRoutingTest {
    @Test
    fun `GUEST-21 an intent replayed from Recents is not handled again, whatever it holds`() {
        for (link in listOf(LINK, null)) {
            for (signedIn in listOf(true, false)) {
                assertEquals(LinkAction.Ignore, route(link, HostSide(), GuestSide(), signedIn, true))
                assertEquals(LinkAction.Ignore, route(link, HOSTING, IN_ROOM, signedIn, true))
            }
        }
    }

    @Test
    fun `GUEST-01 text without a link, or with an http link, is refused`() {
        for (text in listOf(
            "",
            "hello",
            "http://qiyaa.kanstancin.net/j/$ROOM#$SECRET",
            "https://example.org",
        )) {
            assertEquals(
                text,
                LinkAction.Refuse(LinkRefusal.NOT_A_LINK),
                route(
                    JamLink.find(text),
                    HostSide(),
                    GuestSide(),
                    signedIn = true,
                    launchedFromHistory = false,
                ),
            )
        }
    }

    @Test
    fun `GUEST-01 before sign-in the link is kept for after it`() {
        assertEquals(LinkAction.AskSignIn(LINK), route(LINK, HostSide(), GuestSide(), false, false))
        assertEquals(LinkAction.AskSignIn(LINK), route(LINK, HOSTING, IN_ROOM, false, false))
    }

    @Test
    fun `GUEST-20 a link to the room the app hosts opens the host's jam screen`() {
        assertEquals(
            LinkAction.OpenHostJam,
            route(LINK, HostSide(ROOM, active = true), GuestSide(), true, false),
        )
        assertEquals(
            LinkAction.OpenHostJam,
            route(
                JamLink(LINK.serverUrl, ROOM, null),
                HostSide(ROOM, active = true),
                GuestSide(),
                true,
                false,
            ),
        )
    }

    @Test
    fun `GUEST-20 any other link while hosting, starting or a stored host session waits is refused`() {
        val refused = LinkAction.Refuse(LinkRefusal.HOSTING)
        assertEquals(refused, route(LINK, HostSide(OTHER_ROOM, active = true), GuestSide(), true, false))
        assertEquals(refused, route(LINK, HostSide(null, active = true), GuestSide(), true, false))
        assertEquals(refused, route(LINK, HostSide(null, storedSession = true), GuestSide(), true, false))
    }

    @Test
    fun `GUEST-01 a link to the room of the current guest session shows that session`() {
        for (phase in listOf(
            GuestPhase.NeedName,
            GuestPhase.Joining,
            GuestPhase.InRoom,
            GuestPhase.WaitingForRoom,
        )) {
            assertEquals(
                phase.toString(),
                LinkAction.ShowGuest,
                route(LINK, HostSide(), GuestSide(LINK.serverUrl, ROOM, phase), true, false),
            )
        }
        val bare = JamLink(LINK.serverUrl, ROOM, null)
        assertEquals(LinkAction.ShowGuest, route(bare, HostSide(), IN_ROOM, true, false))
    }

    @Test
    fun `GUEST-01 another room, another server or the same room after its end opens the link`() {
        val opened = LinkAction.OpenGuest(LINK)
        assertEquals(opened, route(LINK, HostSide(), GuestSide(), true, false))
        assertEquals(
            opened,
            route(LINK, HostSide(), GuestSide(LINK.serverUrl, OTHER_ROOM, GuestPhase.InRoom), true, false),
        )
        assertEquals(
            opened,
            route(LINK, HostSide(), GuestSide("https://example.org", ROOM, GuestPhase.InRoom), true, false),
        )
        val ends =
            listOf(
                GuestPhase.None,
                GuestPhase.NoSecret,
                GuestPhase.Refused("room-not-found"),
                GuestPhase.Ended(EndReason.HOST_ENDED),
                GuestPhase.Ended(EndReason.EXPIRED),
                GuestPhase.Kicked,
                GuestPhase.UpdateRequired,
            )
        for (phase in ends) {
            assertEquals(
                phase.toString(),
                opened,
                route(LINK, HostSide(), GuestSide(LINK.serverUrl, ROOM, phase), true, false),
            )
        }
    }

    @Test
    fun `GUEST-20 GUEST-05 a session lasts from the link to its end, a link with no secret opens none`() {
        for (phase in listOf(
            GuestPhase.NeedName,
            GuestPhase.Joining,
            GuestPhase.InRoom,
            GuestPhase.WaitingForRoom,
        )) {
            assertTrue(phase.toString(), phase.isSession)
        }
        for (phase in listOf(
            GuestPhase.None,
            GuestPhase.NoSecret,
            GuestPhase.Refused("bad-secret"),
            GuestPhase.Ended(EndReason.HOST_ENDED),
            GuestPhase.Kicked,
            GuestPhase.UpdateRequired,
        )) {
            assertFalse(phase.toString(), phase.isSession)
        }
    }

    private companion object {
        const val ROOM = "7k3m9q2x"
        const val OTHER_ROOM = "8m4n0r3y"
        const val SECRET = "WDkyFgMr5iV3hKwManPvsg"
        val LINK = JamLink("https://qiyaa.kanstancin.net", ROOM, SECRET)
        val HOSTING = HostSide(OTHER_ROOM, active = true)
        val IN_ROOM = GuestSide(LINK.serverUrl, ROOM, GuestPhase.InRoom)
    }
}
