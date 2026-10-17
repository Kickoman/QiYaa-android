package io.github.kickoman.qiyaa.jam

import io.github.kickoman.qiyaa.support.FakeJamSocket
import io.github.kickoman.qiyaa.support.FakeJamTransport
import io.github.kickoman.qiyaa.support.Spec
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JamGuestTest {
    @Test
    fun `GUEST-02 a link to a new room asks for the name and connects nothing`() = runTest {
        val h = Harness(this)
        h.guest.open(LINK)
        runCurrent()
        val state = h.guest.state.value
        assertEquals(GuestPhase.NeedName, state.phase)
        assertEquals(SERVER, state.serverUrl)
        assertEquals(ROOM, state.roomId)
        assertNull(state.lastName)
        assertTrue(h.transport.sockets.isEmpty())
    }

    @Test
    fun `GUEST-05 no secret and no membership says so, connects nothing and cannot join`() = runTest {
        val h = Harness(this)
        h.guest.open(LINK.copy(joinSecret = null))
        assertEquals(GuestPhase.NoSecret, h.phase)
        assertFalse(h.guest.join("Аня"))
        runCurrent()
        assertTrue(h.transport.sockets.isEmpty())
        assertEquals(GuestMemory(), h.memory())
    }

    @Test
    fun `GUEST-02 a name is trimmed and its JavaScript spaces collapsed, 1 to 24 code points`() {
        for (space in JAVASCRIPT_SPACES) {
            val name = "${space}А$space${space}ня$space"
            assertEquals("U+%04X".format(space.code), "А ня", GuestNames.clean(name))
        }
        assertEquals("Аня", GuestNames.clean("  Аня  "))
        assertEquals(GUITAR.repeat(24), GuestNames.clean(GUITAR.repeat(24)))
        assertNull(GuestNames.clean(GUITAR.repeat(25)))
        assertEquals("x".repeat(24), GuestNames.clean(" " + "x".repeat(24) + "\uFEFF"))
        assertNull(GuestNames.clean("x".repeat(25)))
        assertNull(GuestNames.clean(""))
        assertNull(GuestNames.clean(" \u00A0\u3000\uFEFF "))
        for (cleaned in JAVASCRIPT_SPACES.map { GuestNames.clean("${it}Аня$it")!! }) {
            assertTrue(cleaned, JamCodec.decodeClient(JamCodec.encode(joinOf(cleaned))) is Decoded.Message)
        }
    }

    @Test
    fun `GUEST-02 the prefilled name is cleaned and cut to 24 code points`() {
        assertEquals("Аня Ковалёва", GuestNames.prefill(" Аня　 Ковалёва"))
        assertEquals(GUITAR.repeat(24), GuestNames.prefill(GUITAR.repeat(30)))
        assertEquals("x".repeat(23), GuestNames.prefill("x".repeat(23) + " " + "y".repeat(10)))
        assertEquals("", GuestNames.prefill("   "))
        for (name in listOf("Аня Ковалёва", GUITAR.repeat(30), "x".repeat(40))) {
            assertEquals(name, GuestNames.prefill(name), GuestNames.clean(GuestNames.prefill(name)))
        }
    }

    @Test
    fun `GUEST-02 a name that breaks the name rule is not sent`() = runTest {
        val h = Harness(this)
        h.guest.open(LINK)
        assertFalse(h.guest.join("   "))
        assertFalse(h.guest.join("x".repeat(25)))
        assertFalse(h.guest.join("А\u0001ня"))
        runCurrent()
        assertTrue(h.transport.sockets.isEmpty())
        assertEquals(GuestPhase.NeedName, h.phase)
        assertTrue(h.guest.join("\u00A0 Аня \uFEFF"))
        h.online()
        assertEquals("Аня", h.joins().single().name)
    }

    @Test
    fun `GUEST-03 joining stores a membership before the first join, and joined makes it joined`() = runTest {
        val h = Harness(this)
        h.guest.open(LINK)
        assertTrue(h.guest.join("Аня"))
        assertEquals(GuestPhase.Joining, h.phase)
        runCurrent()
        val unconfirmed = GuestMembership(GuestRoom(SERVER, ROOM), PID1, "Аня", SECRET, EPOCH)
        assertEquals(GuestMemory(GuestRoom(SERVER, ROOM), listOf(unconfirmed), "Аня"), h.memory())
        assertEquals("wss://qiyaa.kanstancin.net/ws", h.transport.last.url)
        h.online()
        assertEquals(
            Decoded.Message(Hello(JAM_PROTOCOL, JamApp.ANDROID, "test")),
            JamCodec.decodeClient(h.transport.last.sent.first()),
        )
        assertEquals(listOf(Join("g1", ROOM, SECRET, PID1, "Аня")), h.sent())
        assertEquals(listOf(unconfirmed), h.memory().memberships)
        h.receive(Joined("g1", YOU))
        assertEquals(GuestPhase.InRoom, h.phase)
        assertTrue(h.guest.state.value.connected)
        assertEquals(listOf(unconfirmed.copy(joined = true)), h.memory().memberships)
    }

    @Test
    fun `GUEST-03 the participantId is a lower-case UUID v4 that the protocol accepts`() = runTest {
        val transport = FakeJamTransport()
        val guest =
            JamGuest(
                transport,
                MutableStateFlow(true),
                JamGuestStore(MemoryStore(null)),
                backgroundScope,
                StandardTestDispatcher(testScheduler),
                "test",
            )
        guest.open(LINK)
        guest.join("Аня")
        runCurrent()
        transport.last.open()
        transport.last.receive(JamCodec.encodeServer(Welcome(1, EPOCH)))
        runCurrent()
        val join = JamCodec.decodeClient(transport.last.sent.last())
        val participantId = ((join as Decoded.Message).message as Join).participantId
        assertTrue(participantId, UUID_V4.matches(participantId))
    }

    @Test
    fun `GUEST-01 the guest connects to the link's server, not to the app's`() = runTest {
        val h = Harness(this)
        h.guest.open(JamLink("https://example.org:8443/jam", ROOM, SECRET))
        h.guest.join("Аня")
        runCurrent()
        assertEquals("wss://example.org:8443/jam/ws", h.transport.last.url)
    }

    @Test
    fun `GUEST-01 the session's own link shows it, another room's link leaves it for the new one`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            val first = h.transport.last
            h.guest.open(LINK)
            h.guest.open(LINK.copy(joinSecret = null))
            runCurrent()
            assertEquals(1, h.transport.sockets.size)
            assertEquals(GuestPhase.InRoom, h.phase)
            h.guest.open(JamLink(SERVER, OTHER_ROOM, SECRET))
            runCurrent()
            assertEquals(JamClient.NORMAL_CLOSE, first.closedWith)
            assertEquals(GuestPhase.NeedName, h.phase)
            assertEquals(OTHER_ROOM, h.guest.state.value.roomId)
            assertNull(h.guest.state.value.room)
            assertEquals("Аня", h.guest.state.value.lastName)
            assertEquals(GuestMemory(null, listOf(joinedMembership()), "Аня"), h.memory())
        }

    @Test
    fun `GUEST-04 ROOM-05 another room gets a new participantId and the old membership stays`() = runTest {
        val h = Harness(this)
        h.inRoom()
        h.guest.open(JamLink(SERVER, OTHER_ROOM, OTHER_SECRET))
        h.guest.join("Аня")
        h.online()
        assertEquals(listOf(Join("g2", OTHER_ROOM, OTHER_SECRET, PID2, "Аня")), h.sent())
        assertEquals(listOf(PID2, PID1), h.memory().memberships.map { it.participantId })
    }

    @Test
    fun `GUEST-04 ROOM-13 after a restart a known room joins at once, with the stored secret if need be`() =
        runTest {
            val first = Harness(this)
            first.inRoom()
            first.memory()
            val again = Harness(this, stored = first.file.text, participantIds = emptyList())
            again.guest.open(LINK.copy(joinSecret = null))
            assertEquals(GuestPhase.Joining, again.phase)
            again.online()
            assertEquals(listOf(Join("g1", ROOM, SECRET, PID1, "Аня")), again.sent())
            again.guest.leave()
            again.guest.open(LINK.copy(joinSecret = OTHER_SECRET))
            again.online()
            assertEquals(listOf(Join("g2", ROOM, OTHER_SECRET, PID1, "Аня")), again.sent())
            assertEquals(OTHER_SECRET, again.memory().memberships.single().joinSecret)
        }

    @Test
    fun `GUEST-03 a membership never joined is dropped on any refusal, and the reason shows at once`() =
        runTest {
            val reasons =
                listOf(
                    "room-not-found",
                    "bad-secret",
                    "join-closed",
                    "room-full",
                    "kicked",
                    "rate-limited",
                    "server-full",
                )
            for (reason in reasons) {
                val h = Harness(this)
                h.guest.open(LINK)
                h.guest.join("Аня")
                h.online()
                h.receive(Rejected("g1", reason))
                val expected = if (reason == "kicked") GuestPhase.Kicked else GuestPhase.Refused(reason)
                assertEquals(reason, expected, h.phase)
                assertEquals(reason, JamStatus.STOPPED, h.guest.state.value.connection)
                assertEquals(reason, GuestMemory(lastName = "Аня"), h.memory())
                advanceTimeBy(15 * 60_000)
                runCurrent()
                assertEquals(reason, 1, h.transport.sockets.size)
                assertEquals(reason, 1, h.joins().size)
            }
        }

    @Test
    fun `GUEST-15 REC-13 older states are ignored, a reconnect joins as the same participant`() = runTest {
        val h = Harness(this)
        h.inRoom()
        val away = GUEST_ROOM.copy(hostOnline = false)
        h.state(5, GUEST_ROOM)
        h.state(5, away)
        h.state(4, away)
        assertEquals(GUEST_ROOM, h.guest.state.value.room)
        h.state(6, away)
        assertEquals(away, h.guest.state.value.room)
        h.drop()
        assertEquals(GuestPhase.InRoom, h.phase)
        assertFalse(h.guest.state.value.connected)
        assertEquals(JamStatus.OFFLINE, h.guest.state.value.connection)
        assertEquals(away, h.guest.state.value.room)
        advanceTimeBy(1_000)
        h.online()
        assertEquals(PID1, h.joins().single().participantId)
        h.state(9, GUEST_ROOM)
        assertEquals(away, h.guest.state.value.room)
        h.joined()
        h.state(2, GUEST_ROOM)
        assertEquals(GUEST_ROOM, h.guest.state.value.room)
        h.state(2, away)
        assertEquals(GUEST_ROOM, h.guest.state.value.room)
    }

    @Test
    fun `GUEST-15 adds that wait for a reply are forgotten on a reconnect, and an add offline is not sent`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            assertEquals(GuestAddResult.SENT, h.guest.add(TRACK))
            val refused = TRACK.copy(id = "777")
            assertEquals(GuestAddResult.SENT, h.guest.add(refused))
            h.receive(Rejected(h.sent().filterIsInstance<Add>().last().id, "track-unavailable"))
            h.drop()
            assertEquals(GuestAddResult.NOT_CONNECTED, h.guest.add(TRACK.copy(id = "888")))
            assertEquals(GuestAdd.Sending, h.guest.state.value.adds[TRACK.id])
            advanceTimeBy(1_000)
            h.online()
            h.joined()
            assertEquals(mapOf(refused.id to GuestAdd.Failed("track-unavailable")), h.guest.state.value.adds)
            assertTrue(h.sent().none { it is Add })
        }

    @Test
    fun `GUEST-16 REC-14 a joined membership waits 5, 10, 20, 30, 30 s and gives up after 10 min`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            h.reconnect()
            val socket = h.transport.last
            h.receive(Rejected(h.lastJoinId(), "room-not-found"))
            assertEquals(GuestPhase.WaitingForRoom, h.phase)
            assertEquals(GUEST_ROOM, h.guest.state.value.room)
            val delays = listOf(5_000L, 10_000L, 20_000L) + List(19) { 30_000L }
            for (delayMs in delays) {
                assertEquals(GuestPhase.WaitingForRoom, h.phase)
                val joins = h.joins().size
                advanceTimeBy(delayMs - 1)
                runCurrent()
                assertEquals("still waiting ${delayMs - 1} ms", joins, h.joins().size)
                advanceTimeBy(1)
                runCurrent()
                assertEquals("join again after $delayMs ms", joins + 1, h.joins().size)
                h.receive(Rejected(h.lastJoinId(), "room-not-found"))
            }
            assertSame(socket, h.transport.last)
            assertEquals(GuestPhase.Refused("room-not-found"), h.phase)
            assertEquals(JamStatus.STOPPED, h.guest.state.value.connection)
            assertEquals(GuestMemory(null, listOf(joinedMembership()), "Аня"), h.memory())
            advanceTimeBy(15 * 60_000)
            runCurrent()
            assertEquals(delays.size + 1, h.joins().size)
            assertEquals(2, h.transport.sockets.size)
        }

    @Test
    fun `GUEST-16 the guest backoff varies by a fifth either way`() = runTest {
        for ((random, expectedMs) in listOf(0.0 to 4_000L, 1.0 to 6_000L)) {
            val h = Harness(this)
            h.inRoom()
            h.random = random
            h.reconnect()
            h.receive(Rejected(h.lastJoinId(), "room-not-found"))
            advanceTimeBy(expectedMs - 1)
            runCurrent()
            assertEquals(1, h.joins().size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, h.joins().size)
        }
    }

    @Test
    fun `GUEST-16 REC-14 rate-limited is waited out the same way, and joined ends the wait and its count`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            h.reconnect()
            h.receive(Rejected(h.lastJoinId(), "rate-limited"))
            assertEquals(GuestPhase.WaitingForRoom, h.phase)
            advanceTimeBy(5_000)
            runCurrent()
            h.receive(Rejected(h.lastJoinId(), "room-not-found"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(3, h.joins().size)
            h.joined()
            assertEquals(GuestPhase.InRoom, h.phase)
            assertTrue(h.guest.state.value.connected)
            h.reconnect()
            h.receive(Rejected(h.lastJoinId(), "room-not-found"))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(2, h.joins().size)
        }

    @Test
    fun `GUEST-16 a reconnect during the wait joins, and the old deadline sends no second join`() = runTest {
        val h = Harness(this)
        h.inRoom()
        h.reconnect()
        h.receive(Rejected(h.lastJoinId(), "room-not-found"))
        advanceTimeBy(5_000)
        runCurrent()
        h.receive(Rejected(h.lastJoinId(), "room-not-found"))
        advanceTimeBy(2_000)
        h.drop()
        advanceTimeBy(1_000)
        h.online()
        assertEquals(1, h.joins().size)
        h.joined()
        assertEquals(GuestPhase.InRoom, h.phase)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.joins().size)
        assertEquals(GuestPhase.InRoom, h.phase)
        assertTrue(h.guest.state.value.connected)
    }

    @Test
    fun `GUEST-16 no timed join while the join of a new connection waits for its reply`() = runTest {
        val h = Harness(this)
        h.inRoom()
        h.reconnect()
        h.receive(Rejected(h.lastJoinId(), "room-not-found"))
        h.drop()
        advanceTimeBy(1_000)
        h.online()
        assertEquals(1, h.joins().size)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.joins().size)
        assertEquals(GuestPhase.WaitingForRoom, h.phase)
    }

    @Test
    fun `GUEST-16 a not-allowed for a join means the connection has joined, and it ends nothing`() = runTest {
        val h = Harness(this)
        h.guest.open(LINK)
        h.guest.join("Аня")
        h.online()
        h.receive(Rejected("g1", "not-allowed"))
        assertEquals(GuestPhase.InRoom, h.phase)
        assertTrue(h.guest.state.value.connected)
        assertEquals(JamStatus.ONLINE, h.guest.state.value.connection)
        assertEquals(listOf(joinedMembership()), h.memory().memberships)
        h.state(1)
        assertEquals(GUEST_ROOM, h.guest.state.value.room)
    }

    @Test
    fun `GUEST-17 after host-ended the membership is ended, and room-not-found is final at once`() = runTest {
        val h = Harness(this)
        h.inRoom()
        h.receive(Ended(EndReason.HOST_ENDED))
        assertEquals(GuestPhase.Ended(EndReason.HOST_ENDED), h.phase)
        assertEquals(GuestMemory(null, listOf(joinedMembership().copy(ended = true)), "Аня"), h.memory())
        h.guest.open(LINK)
        assertEquals(GuestPhase.Joining, h.phase)
        h.online()
        assertEquals(PID1, h.joins().single().participantId)
        h.receive(Rejected(h.lastJoinId(), "room-not-found"))
        assertEquals(GuestPhase.Ended(EndReason.HOST_ENDED), h.phase)
        assertEquals(JamStatus.STOPPED, h.guest.state.value.connection)
        advanceTimeBy(15 * 60_000)
        runCurrent()
        assertEquals(1, h.joins().size)
        assertEquals(2, h.transport.sockets.size)
        assertEquals(listOf(joinedMembership().copy(ended = true)), h.memory().memberships)
    }

    @Test
    fun `GUEST-17 REC-15 kicked, ended and update-required stop for good, the membership stays`() = runTest {
        val cases =
            listOf(
                Kicked to GuestPhase.Kicked,
                Ended(EndReason.EXPIRED) to GuestPhase.Ended(EndReason.EXPIRED),
                Ended(EndReason.HOST_ENDED) to GuestPhase.Ended(EndReason.HOST_ENDED),
                Rejected(reason = "update-required", serverProtocol = 2) to GuestPhase.UpdateRequired,
            )
        for ((message, phase) in cases) {
            val h = Harness(this)
            h.inRoom()
            h.receive(message)
            assertEquals("$message", phase, h.phase)
            assertEquals("$message", JamStatus.STOPPED, h.guest.state.value.connection)
            assertFalse(h.guest.state.value.connected)
            h.transport.last.drop()
            advanceTimeBy(15 * 60_000)
            runCurrent()
            assertEquals("$message", 1, h.transport.sockets.size)
            val memory = h.memory()
            assertNull(memory.current)
            assertEquals("$message", PID1, memory.memberships.single().participantId)
            assertEquals("$message", GuestAddResult.NOT_CONNECTED, h.guest.add(TRACK))
        }
    }

    @Test
    fun `GUEST-17 ROOM-08 after a kick the link sends the same participantId and is kicked again`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            h.receive(Kicked)
            h.guest.open(LINK.copy(joinSecret = null))
            h.online()
            assertEquals(PID1, h.joins().single().participantId)
            h.receive(Rejected(h.lastJoinId(), "kicked"))
            assertEquals(GuestPhase.Kicked, h.phase)
            assertEquals(JamStatus.STOPPED, h.guest.state.value.connection)
            assertEquals(listOf(joinedMembership()), h.memory().memberships)
        }

    @Test
    fun `GUEST-17 REC-06 after expired the same participant comes back, room-not-found waits`() = runTest {
        val h = Harness(this)
        h.inRoom()
        h.receive(Ended(EndReason.EXPIRED))
        assertEquals(listOf(joinedMembership()), h.memory().memberships)
        h.guest.open(LINK)
        h.online()
        assertEquals(PID1, h.joins().single().participantId)
        h.receive(Rejected(h.lastJoinId(), "room-not-found"))
        assertEquals(GuestPhase.WaitingForRoom, h.phase)
        advanceTimeBy(5_000)
        runCurrent()
        h.joined()
        assertEquals(GuestPhase.InRoom, h.phase)
    }

    @Test
    fun `other refusals of a joined membership end the session and keep the membership`() = runTest {
        for (reason in listOf("bad-secret", "join-closed", "room-full", "server-full")) {
            val h = Harness(this)
            h.inRoom()
            h.guest.leave()
            h.guest.open(LINK)
            h.online()
            h.receive(Rejected(h.lastJoinId(), reason))
            assertEquals(reason, GuestPhase.Refused(reason), h.phase)
            assertEquals(reason, JamStatus.STOPPED, h.guest.state.value.connection)
            assertEquals(reason, GuestMemory(null, listOf(joinedMembership()), "Аня"), h.memory())
        }
    }

    @Test
    fun `GUEST-07 ROOM-65 add carries the track, never a trackId, and the reply lands on it`() = runTest {
        val h = Harness(this)
        h.inRoom()
        assertEquals(GuestAddResult.SENT, h.guest.add(TRACK))
        val add = h.sent().last() as Add
        assertEquals(Add(add.id, track = TRACK), add)
        assertFalse(h.transport.last.sent.last().contains("trackId"))
        assertEquals(GuestAdd.Sending, h.guest.state.value.adds[TRACK.id])
        h.receive(Ack(add.id))
        assertEquals(GuestAdd.Added, h.guest.state.value.adds[TRACK.id])
        val reasons =
            listOf(
                "track-unavailable", "host-offline", "host-timeout", "host-error", "duplicate",
                "queue-limit", "rate-limited", "not-allowed", "too-loud",
            )
        for ((index, reason) in reasons.withIndex()) {
            val track = TRACK.copy(id = "${index + 1}")
            assertEquals(GuestAddResult.SENT, h.guest.add(track))
            h.receive(Rejected((h.sent().last() as Add).id, reason))
            assertEquals(reason, GuestAdd.Failed(reason), h.guest.state.value.adds[track.id])
        }
        assertTrue(h.events.isEmpty())
        assertEquals(GuestPhase.InRoom, h.phase)
    }

    @Test
    fun `GUEST-07 ROOM-66 a host track, a broken one and one over the 4 KiB guest frame are not sent`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            val hostTrack =
                JamTrack(
                    id = "local-1",
                    title = "Демо",
                    artists = listOf("Я"),
                    durationMs = 1_000,
                    source = JamTrackSource.HOST,
                )
            val huge =
                TRACK.copy(
                    title = GUITAR.repeat(150),
                    artists = List(10) { GUITAR.repeat(64) },
                    coverUri = "%%" + "漢".repeat(298),
                )
            val hugeAdd = JamCodec.encode(Add("g".repeat(36), track = huge))
            assertTrue(JamCodec.decodeClient(hugeAdd) is Decoded.Message)
            val bytes = hugeAdd.toByteArray(Charsets.UTF_8).size
            assertTrue("$bytes bytes", bytes > JamGuest.GUEST_FRAME_BYTES)
            for (track in listOf(hostTrack, TRACK.copy(title = ""), TRACK.copy(albumId = "x y"), huge)) {
                assertEquals(track.title, GuestAddResult.INVALID, h.guest.add(track))
            }
            assertTrue(h.sent().none { it is Add })
            assertTrue(h.guest.state.value.adds.isEmpty())
        }

    @Test
    fun `GUEST-09 GUEST-15 an add is not sent before joined or while the host is offline`() = runTest {
        val h = Harness(this)
        assertEquals(GuestAddResult.NOT_CONNECTED, h.guest.add(TRACK))
        h.guest.open(LINK)
        h.guest.join("Аня")
        h.online()
        assertEquals(GuestAddResult.NOT_CONNECTED, h.guest.add(TRACK))
        h.joined()
        assertEquals(GuestAddResult.NOT_CONNECTED, h.guest.add(TRACK))
        h.state(1, GUEST_ROOM.copy(hostOnline = false))
        assertEquals(GuestAddResult.HOST_OFFLINE, h.guest.add(TRACK))
        assertEquals(AddAction.HostOffline, h.guest.state.value.addAction(TRACK.id))
        h.state(2, GUEST_ROOM)
        assertEquals(GuestAddResult.SENT, h.guest.add(TRACK))
        assertEquals(1, h.sent().count { it is Add })
    }

    @Test
    fun `GUEST-10 ROOM-28 remove only the guest's own waiting item, and a refusal is an event`() = runTest {
        val h = Harness(this)
        h.inRoom()
        val state = h.guest.state.value
        assertEquals(listOf(false, false, true), GUEST_ROOM.queue.map(state::canRemove))
        assertFalse(h.guest.remove("i4"))
        assertFalse(h.guest.remove("i6"))
        assertFalse(h.guest.remove("i3"))
        assertFalse(h.guest.remove("i2"))
        assertTrue(h.guest.remove("i5"))
        val remove = h.sent().last() as Remove
        assertEquals("i5", remove.itemId)
        h.receive(Ack(remove.id))
        assertTrue(h.events.isEmpty())
        assertTrue(h.guest.remove("i5"))
        h.receive(Rejected((h.sent().last() as Remove).id, "not-allowed"))
        assertEquals(listOf<JamGuestEvent>(JamGuestEvent.Refused("not-allowed")), h.events)
        assertEquals(GuestPhase.InRoom, h.phase)
    }

    @Test
    fun `GUEST-11 ROOM-43 to ROOM-46 skip only if allowed, for the current item, with the host online`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            assertFalse(h.guest.state.value.canSkip)
            assertFalse(h.guest.skip("i3"))
            val skipping = GUEST_ROOM.copy(settings = GUEST_ROOM.settings.copy(guestsCanSkip = true))
            h.state(2, skipping)
            assertTrue(h.guest.state.value.canSkip)
            assertFalse(h.guest.skip("i4"))
            assertTrue(h.guest.skip("i3"))
            assertEquals(Skip((h.sent().last() as Skip).id, "i3"), h.sent().last())
            val wave =
                skipping.nowPlaying.copy(source = NowPlayingSource.WAVE, itemId = null, addedBy = null)
            h.state(3, skipping.copy(nowPlaying = wave))
            assertFalse(h.guest.state.value.canSkip)
            assertFalse(h.guest.skip("i3"))
            h.state(4, skipping.copy(hostOnline = false))
            assertFalse(h.guest.skip("i3"))
            h.state(5, skipping)
            h.drop()
            assertFalse(h.guest.skip("i3"))
            assertEquals(1, h.transport.sockets.first().sent.count { it.contains("\"type\":\"skip\"") })
        }

    @Test
    fun `ROOM-27 GUEST-06 the guest sends only join, add, remove and skip, never search`() = runTest {
        val h = Harness(this)
        h.inRoom()
        h.state(2, GUEST_ROOM.copy(settings = GUEST_ROOM.settings.copy(guestsCanSkip = true)))
        h.guest.add(TRACK)
        h.guest.remove("i5")
        h.guest.skip("i3")
        h.reconnect()
        h.joined()
        h.guest.leave()
        val types =
            h.transport.sockets.flatMap(FakeJamSocket::sent).map {
                ((JamCodec.decodeClient(it) as Decoded.Message).message)::class.simpleName
            }
        assertEquals(setOf("Hello", "Join", "Add", "Remove", "Skip"), types.toSet())
        val frames = h.transport.sockets.flatMap(FakeJamSocket::sent)
        assertTrue(frames.none { it.contains("\"type\":\"search\"") })
    }

    @Test
    fun `GUEST-18 ROOM-11 leave closes with 1000, keeps the membership, and the link joins at once`() =
        runTest {
            val h = Harness(this)
            h.inRoom()
            val first = h.transport.last
            h.guest.leave()
            runCurrent()
            assertEquals(JamClient.NORMAL_CLOSE, first.closedWith)
            assertEquals(JamGuestState(lastName = "Аня", connection = JamStatus.STOPPED), h.guest.state.value)
            assertEquals(GuestMemory(null, listOf(joinedMembership()), "Аня"), h.memory())
            h.guest.open(LINK.copy(joinSecret = null))
            assertEquals(GuestPhase.Joining, h.phase)
            h.online()
            assertEquals(listOf(Join(h.lastJoinId(), ROOM, SECRET, PID1, "Аня")), h.sent())
        }

    @Test
    fun `GUEST-18 leave while joining closes the connection and keeps the membership`() = runTest {
        val h = Harness(this)
        h.guest.open(LINK)
        h.guest.join("Аня")
        runCurrent()
        h.guest.leave()
        assertEquals(JamClient.NORMAL_CLOSE, h.transport.last.closedWith)
        assertEquals(GuestPhase.None, h.phase)
        assertEquals(1, h.memory().memberships.size)
        assertFalse(h.guest.restore())
    }

    @Test
    fun `GUEST-19 a session under 12 h old is restored and joins at once, an older one is not`() = runTest {
        val life = JamGuestStore.MEMBERSHIP_LIFE_MS
        val young = joinedMembership().copy(firstJoinedAt = EPOCH - life + 60_000)
        val h = Harness(this, stored = JamGuestCodec.encode(GuestMemory(young.room, listOf(young), "Аня")))
        assertTrue(h.guest.restore())
        assertEquals(GuestPhase.Joining, h.phase)
        h.online()
        assertEquals(listOf(Join("g1", ROOM, SECRET, PID1, "Аня")), h.sent())
        assertTrue(h.guest.restore())
        runCurrent()
        assertEquals(1, h.transport.sockets.size)

        val old = young.copy(firstJoinedAt = EPOCH - life)
        val late = Harness(this, stored = JamGuestCodec.encode(GuestMemory(old.room, listOf(old), "Аня")))
        assertFalse(late.guest.restore())
        runCurrent()
        assertTrue(late.transport.sockets.isEmpty())
        assertEquals(GuestPhase.None, late.phase)
        assertFalse(Harness(this).guest.restore())
    }

    @Test
    fun `GUEST-03 sign-out leaves the room and forgets every membership and the name`() = runTest {
        val h = Harness(this)
        h.inRoom()
        h.guest.forgetAll()
        runCurrent()
        assertEquals(JamClient.NORMAL_CLOSE, h.transport.last.closedWith)
        assertNull(h.file.text)
        assertEquals(JamGuestState(connection = JamStatus.STOPPED), h.guest.state.value)
        h.guest.open(LINK.copy(joinSecret = null))
        assertEquals(GuestPhase.NoSecret, h.phase)
    }

    @Test
    fun `GUEST-08 the add action is the first case that applies`() {
        val state = JamGuestState(phase = GuestPhase.InRoom, connected = true, room = GUEST_ROOM)
        val away = state.copy(room = GUEST_ROOM.copy(hostOnline = false))
        val oneEach = GUEST_ROOM.settings.copy(maxPendingPerGuest = 1)
        val full = state.copy(room = GUEST_ROOM.copy(settings = oneEach))
        fun JamGuestState.adding(add: GuestAdd) =
            copy(adds = listOf("1", "4426586", "1695498").associateWith { add })

        assertEquals(1, state.waitingOfYours)
        assertEquals(AddAction.Yours, away.adding(GuestAdd.Sending).addAction("4426586"))
        assertEquals(AddAction.QueuedBy("Боря"), full.adding(GuestAdd.Sending).addAction("1695498"))
        assertEquals(AddAction.QueuedBy("Маша"), state.addAction("39089632"))
        val stranger = GUEST_ROOM.queue.map { if (it.itemId == "i4") it.copy(addedBy = "zzzzzz") else it }
        val strangers = state.copy(room = GUEST_ROOM.copy(queue = stranger))
        assertEquals(AddAction.QueuedBy(null), strangers.addAction("1695498"))
        assertEquals(AddAction.Sending, away.adding(GuestAdd.Sending).addAction("1"))
        val fullAndAway = away.copy(room = full.room!!.copy(hostOnline = false))
        assertEquals(AddAction.HostOffline, fullAndAway.addAction("1"))
        assertEquals(AddAction.Limit(1), full.adding(GuestAdd.Failed("track-unavailable")).addAction("1"))
        assertEquals(
            AddAction.Failed("track-unavailable"),
            state.adding(GuestAdd.Failed("track-unavailable")).addAction("1"),
        )
        assertEquals(AddAction.Failed("too-loud"), state.adding(GuestAdd.Failed("too-loud")).addAction("1"))
        assertEquals(AddAction.Add, state.adding(GuestAdd.Failed("duplicate")).addAction("1"))
        assertEquals(AddAction.Add, state.adding(GuestAdd.Failed("queue-limit")).addAction("1"))
        assertEquals(AddAction.Add, state.adding(GuestAdd.Added).addAction("1"))
        assertEquals(AddAction.Add, state.addAction("1"))
        assertNull(JamGuestState().addAction("1"))
    }

    @Test
    fun `GUEST-14 the position runs on the room's clock, stands while paused and stays within the track`() {
        val playing = GUEST_ROOM.nowPlaying
        val at = playing.reportedAt
        assertEquals(42_000L, playing.positionMsAt(at))
        assertEquals(45_000L, playing.positionMsAt(at + 3_000))
        assertEquals(42_000L, playing.copy(paused = true).positionMsAt(at + 3_000))
        assertEquals(235_100L, playing.positionMsAt(at + 1_000_000))
        assertEquals(0L, playing.positionMsAt(at - 100_000))
        val idle = JamNowPlaying(NowPlayingSource.IDLE, positionMs = 0, paused = true, reportedAt = at)
        assertEquals(0L, idle.positionMsAt(at + 5_000))
    }

    @Test
    fun `the guest example state drives a room where the app is not the host`() = runTest {
        val h = Harness(this)
        h.inRoom()
        val state = h.guest.state.value
        assertEquals(GUEST_ROOM, state.room)
        assertFalse(state.room!!.you.isHost)
        assertEquals(YOU, state.room.you.publicId)
        assertEquals(AddAction.Yours, state.addAction("4426586"))
        assertEquals(AddAction.QueuedBy("Боря"), state.addAction("1695498"))
        assertEquals(AddAction.Add, state.addAction("38633712"))
    }

    private class Harness(
        val scope: TestScope,
        stored: String? = null,
        participantIds: List<String> = listOf(PID1, PID2, PID3),
    ) {
        val transport = FakeJamTransport()
        val file = MemoryStore(stored)
        val events = ArrayList<JamGuestEvent>()
        var random = 0.5
        private val freshIds = ArrayDeque(participantIds)
        private var requestIds = 0
        val guest =
            JamGuest(
                transport = transport,
                connectivity = MutableStateFlow(true),
                store = JamGuestStore(file),
                scope = scope.backgroundScope,
                io = StandardTestDispatcher(scope.testScheduler),
                appVersion = "test",
                newParticipantId = { freshIds.removeFirst() },
                newId = { "g${++requestIds}" },
                random = { random },
                clock = { EPOCH + scope.testScheduler.currentTime },
            )

        init {
            scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                guest.events.collect { events.add(it) }
            }
        }

        val phase: GuestPhase get() = guest.state.value.phase

        fun sent(): List<ClientMessage> = transport.last.sent
            .map { (JamCodec.decodeClient(it) as Decoded.Message).message }
            .filterNot { it is Hello }

        fun joins(): List<Join> = sent().filterIsInstance<Join>()

        fun lastJoinId(): String = joins().last().id

        fun online() {
            scope.runCurrent()
            transport.last.open()
            receive(Welcome(1, EPOCH + scope.testScheduler.currentTime))
        }

        fun receive(message: ServerMessage) {
            transport.last.receive(JamCodec.encodeServer(message))
            scope.runCurrent()
        }

        fun joined() = receive(Joined(lastJoinId(), YOU))

        fun state(version: Long, room: JamRoom = GUEST_ROOM) = receive(State(version, EPOCH, room))

        fun inRoom() {
            guest.open(LINK)
            guest.join("Аня")
            online()
            joined()
            state(1)
        }

        fun drop() {
            transport.last.drop()
            scope.runCurrent()
        }

        fun reconnect() {
            drop()
            scope.advanceTimeBy(1_000)
            online()
        }

        fun memory(): GuestMemory {
            scope.runCurrent()
            return JamGuestCodec.decode(file.text) ?: GuestMemory()
        }
    }

    private class MemoryStore(var text: String?) : JamStore {
        override fun read(): String? = text

        override fun write(text: String?) {
            this.text = text
        }
    }

    private companion object {
        const val SERVER = "https://qiyaa.kanstancin.net"
        const val ROOM = "7k3m9q2x"
        const val OTHER_ROOM = "9q2x7k3m"
        const val SECRET = "WDkyFgMr5iV3hKwManPvsg"
        const val OTHER_SECRET = "YQnwcZFPKIIzsWAyIuVGGA"
        const val YOU = "a4n8q1"
        const val PID1 = "3f6c1a2e-8b4d-4c7a-9e21-5d0b7f3a1c64"
        const val PID2 = "0a1b2c3d-4e5f-4a7b-8c9d-0e1f2a3b4c5d"
        const val PID3 = "9e8d7c6b-5a49-4837-a625-14f3e2d1c0b9"
        const val EPOCH = 1_790_730_000_000L
        const val GUITAR = "\uD83C\uDFB8"
        const val JAVASCRIPT_SPACES =
            "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006" +
                "\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"
        val LINK = JamLink(SERVER, ROOM, SECRET)
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val TRACK =
            JamTrack(
                id = "38633712",
                albumId = "5307396",
                title = "Группа крови",
                artists = listOf("КИНО"),
                durationMs = 235_100,
                coverUri = "avatars.yandex.net/get-music-content/95061/4f3808a0.a.5307396-3/%%",
            )

        val GUEST_ROOM: JamRoom by lazy {
            val text = File(Spec.root, "jam/protocol/examples/state/guest.json").readText()
            ((JamCodec.decodeServer(text) as Decoded.Message).message as State).room
        }

        fun joinOf(name: String) = Join("g1", ROOM, SECRET, PID1, name)

        fun joinedMembership() =
            GuestMembership(GuestRoom(SERVER, ROOM), PID1, "Аня", SECRET, EPOCH, joined = true)
    }
}
