package io.github.kickoman.qiyaa.ui

import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.jam.Decoded
import io.github.kickoman.qiyaa.jam.EndReason
import io.github.kickoman.qiyaa.jam.GuestAddResult
import io.github.kickoman.qiyaa.jam.GuestPhase
import io.github.kickoman.qiyaa.jam.JamCodec
import io.github.kickoman.qiyaa.jam.JamGuestState
import io.github.kickoman.qiyaa.jam.JamRoom
import io.github.kickoman.qiyaa.jam.JamStatus
import io.github.kickoman.qiyaa.jam.State
import io.github.kickoman.qiyaa.support.Spec
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JamEventTextTest {
    @Test
    fun `every reason of the protocol has a text of its own`() {
        val defs = File(Spec.root, "jam/protocol/schemas/defs.schema.json").readText()
        val reasons =
            Json.parseToJsonElement(defs).jsonObject.getValue("\$defs").jsonObject.getValue("reason")
                .jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(18, reasons.size)
        for (reason in reasons) {
            assertNotEquals(reason, R.string.jam_refused_other, refusedText(reason))
            assertNotEquals(reason, R.string.jam_refused_other, guestRefusedText(reason))
        }
        assertEquals(R.string.jam_refused_other, refusedText("no-such-reason"))
    }

    @Test
    fun `GUEST-08 queue-limit tells a guest about its own limit, other reasons read as for the host`() {
        assertEquals(R.string.jam_refused_guest_queue_limit, guestRefusedText("queue-limit"))
        assertEquals(R.string.jam_refused_queue_limit, refusedText("queue-limit"))
        for (reason in listOf("track-unavailable", "host-offline", "duplicate", "kicked", "no-such-reason")) {
            assertEquals(reason, refusedText(reason), guestRefusedText(reason))
        }
    }

    @Test
    fun `GUEST-05 GUEST-17 each end of a guest session says why, and a live one has no end text`() {
        assertEquals(R.string.jam_guest_no_secret, GuestPhase.NoSecret.endText())
        assertEquals(R.string.jam_refused_bad_secret, GuestPhase.Refused("bad-secret").endText())
        assertEquals(R.string.jam_refused_guest_queue_limit, GuestPhase.Refused("queue-limit").endText())
        assertEquals(R.string.jam_guest_ended_host, GuestPhase.Ended(EndReason.HOST_ENDED).endText())
        assertEquals(R.string.jam_guest_ended_expired, GuestPhase.Ended(EndReason.EXPIRED).endText())
        assertEquals(R.string.jam_guest_kicked, GuestPhase.Kicked.endText())
        assertEquals(R.string.jam_refused_update_required, GuestPhase.UpdateRequired.endText())
        for (phase in listOf(
            GuestPhase.None,
            GuestPhase.NeedName,
            GuestPhase.Joining,
            GuestPhase.InRoom,
            GuestPhase.WaitingForRoom,
        )) {
            assertNull(phase.toString(), phase.endText())
        }
    }

    @Test
    fun `GUEST-16 a wait for the room says the server lost the jam, with or without a room on screen`() {
        val waiting = JamGuestState(phase = GuestPhase.WaitingForRoom, connection = JamStatus.ONLINE)
        assertEquals(R.string.jam_guest_waiting_for_room, waiting.statusText())
        assertEquals(R.string.jam_guest_waiting_for_room, waiting.copy(room = room()).statusText())
        assertEquals(
            R.string.jam_guest_waiting_for_room,
            waiting.copy(connection = JamStatus.OFFLINE, room = room(hostOnline = false)).statusText(),
        )
    }

    @Test
    fun `GUEST-15 GUEST-09 in the room the status says no connection, then that the host is away`() {
        val inRoom = JamGuestState(phase = GuestPhase.InRoom, connected = true, room = room())
        assertNull(inRoom.statusText())
        val away = inRoom.copy(room = room(hostOnline = false))
        assertEquals(R.string.jam_guest_host_offline, away.statusText())
        assertEquals(R.string.jam_offline_strip, inRoom.copy(connected = false).statusText())
        assertEquals(R.string.jam_offline_strip, away.copy(connected = false).statusText())
    }

    @Test
    fun `GUEST-03 GUEST-17 no status line while joining, before the first state, or after the end`() {
        assertNull(JamGuestState(phase = GuestPhase.Joining, connection = JamStatus.CONNECTING).statusText())
        assertNull(JamGuestState(phase = GuestPhase.InRoom, connected = true).statusText())
        assertNull(JamGuestState(phase = GuestPhase.InRoom, connected = false).statusText())
        for (phase in listOf(
            GuestPhase.None,
            GuestPhase.NoSecret,
            GuestPhase.NeedName,
            GuestPhase.Refused("room-not-found"),
            GuestPhase.Ended(EndReason.EXPIRED),
            GuestPhase.Kicked,
        )) {
            assertNull(phase.toString(), JamGuestState(phase = phase, room = room()).statusText())
        }
    }

    @Test
    fun `GUEST-07 GUEST-09 every add that is not sent says why`() {
        assertNull(GuestAddResult.SENT.text())
        val texts = (GuestAddResult.entries - GuestAddResult.SENT).map { it.text() }
        assertTrue(texts.all { it != null })
        assertEquals(texts.size, texts.toSet().size)
        assertEquals(R.string.jam_guest_add_invalid, GuestAddResult.INVALID.text())
    }

    private fun room(hostOnline: Boolean = true): JamRoom {
        val text = File(Spec.root, "jam/protocol/examples/state/guest.json").readText()
        val room = ((JamCodec.decodeServer(text) as Decoded.Message).message as State).room
        return room.copy(hostOnline = hostOnline)
    }
}
