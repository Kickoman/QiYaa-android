package io.github.kickoman.qiyaa.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JamGuestStoreTest {
    @Test
    fun `GUEST-03 memberships, the current room and the last name survive encode and decode`() {
        val memory =
            GuestMemory(
                current = ROOM,
                memberships =
                listOf(
                    membership(ROOM, NOW, joined = true),
                    membership(OTHER, NOW - 1_000, joined = true, ended = true),
                    membership(GuestRoom("https://example.org:8443/jam", "a1b2c3d4"), NOW - 2_000),
                ),
                lastName = "Аня",
            )
        assertEquals(memory, JamGuestCodec.decode(JamGuestCodec.encode(memory)))
        assertEquals(GuestMemory(), JamGuestCodec.decode(JamGuestCodec.encode(GuestMemory())))
    }

    @Test
    fun `the file format is the one in the README`() {
        val memory = GuestMemory(ROOM, listOf(membership(ROOM, 1_790_730_000_000, joined = true)), "Аня")
        assertEquals(
            """{"version":1,"current":{"serverUrl":"$SERVER","roomId":"7k3m9q2x"},""" +
                """"lastName":"Аня","rooms":[{"serverUrl":"$SERVER","roomId":"7k3m9q2x",""" +
                """"participantId":"$PARTICIPANT","name":"Аня","joinSecret":"$SECRET",""" +
                """"firstJoinedAt":1790730000000,"joined":true}]}""",
            JamGuestCodec.encode(memory),
        )
    }

    @Test
    fun `a missing, broken or foreign file is no memberships`() {
        assertNull(JamGuestCodec.decode(null))
        assertNull(JamGuestCodec.decode(""))
        assertNull(JamGuestCodec.decode("{not json"))
        assertNull(JamGuestCodec.decode("""{"version":2,"rooms":[]}"""))
        assertNull(JamGuestCodec.decode("""{"version":1,"rooms":7}"""))
        assertEquals(GuestMemory(), store("{not json").load(NOW))
        assertEquals(GuestMemory(), store(null).load(NOW))
    }

    @Test
    fun `an entry the server would refuse is dropped, and the current room goes with it`() {
        val broken =
            listOf(
                membership(ROOM, NOW).copy(participantId = PARTICIPANT.uppercase()),
                membership(ROOM, NOW).copy(name = " Аня"),
                membership(ROOM, NOW).copy(joinSecret = "short"),
                membership(GuestRoom("http://qiyaa.kanstancin.net", "7k3m9q2x"), NOW),
                membership(GuestRoom("https://Qiyaa.kanstancin.net/", "7k3m9q2x"), NOW),
                membership(GuestRoom("https://qiyaa.kanstancin.net", "7K3M9Q2X"), NOW),
            )
        for (entry in broken) {
            val decoded = JamGuestCodec.decode(JamGuestCodec.encode(GuestMemory(entry.room, listOf(entry))))
            assertEquals(entry.toString(), GuestMemory(), decoded)
        }
        val kept = membership(OTHER, NOW)
        val mixed = GuestMemory(ROOM, listOf(broken[0], kept), "Аня")
        assertEquals(
            GuestMemory(null, listOf(kept), "Аня"),
            JamGuestCodec.decode(JamGuestCodec.encode(mixed)),
        )
    }

    @Test
    fun `GUEST-03 GUEST-19 a load forgets memberships of 12 h and older and keeps the 20 newest`() {
        val life = JamGuestStore.MEMBERSHIP_LIFE_MS
        val young = membership(ROOM, NOW - life + 1, joined = true)
        val old = membership(OTHER, NOW - life, joined = true)
        assertEquals(
            GuestMemory(ROOM, listOf(young), "Аня"),
            store(JamGuestCodec.encode(GuestMemory(ROOM, listOf(old, young), "Аня"))).load(NOW),
        )
        assertEquals(
            GuestMemory(null, listOf(young), "Аня"),
            store(JamGuestCodec.encode(GuestMemory(OTHER, listOf(young, old), "Аня"))).load(NOW),
        )
        val many = (0 until 25).map { membership(GuestRoom(SERVER, roomId(it)), NOW - it * 60_000L) }
        val loaded = store(JamGuestCodec.encode(GuestMemory(memberships = many.reversed()))).load(NOW)
        assertEquals(many.take(JamGuestStore.MAX_MEMBERSHIPS), loaded.memberships)
    }

    @Test
    fun `with puts a new membership first and replaces a known one in place, without forgets one room`() {
        val first = membership(ROOM, NOW - 1_000)
        val second = membership(OTHER, NOW)
        val memory = GuestMemory().with(first).with(second)
        assertEquals(listOf(second, first), memory.memberships)
        assertEquals(
            listOf(second, first.copy(joined = true)),
            memory.with(first.copy(joined = true)).memberships,
        )
        assertEquals(first, memory.membership(ROOM))
        assertEquals(GuestMemory(OTHER, listOf(second)), memory.copy(current = OTHER).without(ROOM))
        assertEquals(GuestMemory(null, listOf(first)), memory.copy(current = OTHER).without(OTHER))
    }

    @Test
    fun `clear deletes the file`() {
        val memory = GuestMemory(ROOM, listOf(membership(ROOM, NOW)), "Аня")
        val file = MemoryStore(null)
        val store = JamGuestStore(file)
        store.save(memory)
        assertEquals(memory, store.load(NOW))
        store.clear()
        assertNull(file.text)
        assertEquals(GuestMemory(), store.load(NOW))
    }

    private fun store(text: String?) = JamGuestStore(MemoryStore(text))

    private fun membership(
        room: GuestRoom,
        firstJoinedAt: Long,
        joined: Boolean = false,
        ended: Boolean = false,
    ) = GuestMembership(room, PARTICIPANT, "Аня", SECRET, firstJoinedAt, joined, ended)

    private fun roomId(index: Int): String = "a1b2c3" + "0123456789abcdefghjkmnpqrstvwxyz"[index] + "0"

    private class MemoryStore(var text: String?) : JamStore {
        override fun read(): String? = text

        override fun write(text: String?) {
            this.text = text
        }
    }

    private companion object {
        const val SERVER = "https://qiyaa.kanstancin.net"
        const val NOW = 1_790_730_000_000L
        const val PARTICIPANT = "3f6c1a2e-8b4d-4c7a-9e21-5d0b7f3a1c64"
        const val SECRET = "WDkyFgMr5iV3hKwManPvsg"
        val ROOM = GuestRoom(SERVER, "7k3m9q2x")
        val OTHER = GuestRoom(SERVER, "9q2x7k3m")
    }
}
