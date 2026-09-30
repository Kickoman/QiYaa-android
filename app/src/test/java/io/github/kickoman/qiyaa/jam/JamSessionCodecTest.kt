package io.github.kickoman.qiyaa.jam

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JamSessionCodecTest {
    @Test
    fun `a session survives encode and decode, snapshot and outbox included`() {
        val snapshot = buildJsonObject { put("format", JsonPrimitive(1)) }
        val session =
            JamSession(
                "7k3m9q2x",
                "H".repeat(43),
                "https://jam.example.org/j/7k3m9q2x#s",
                snapshot,
                listOf("i4", "i5"),
            )
        assertEquals(session, JamSessionCodec.decode(JamSessionCodec.encode(session)))
        val bare = session.copy(snapshot = null, outbox = emptyList())
        assertEquals(bare, JamSessionCodec.decode(JamSessionCodec.encode(bare)))
    }

    @Test
    fun `a missing, broken or foreign file is no stored jam`() {
        assertNull(JamSessionCodec.decode(null))
        assertNull(JamSessionCodec.decode(""))
        assertNull(JamSessionCodec.decode("{not json"))
        assertNull(JamSessionCodec.decode("""{"version":2,"roomId":"r","hostSecret":"s","joinUrl":"u"}"""))
        assertNull(JamSessionCodec.decode("""{"version":1,"roomId":7}"""))
    }

    @Test
    fun `the store clears with null`() {
        var stored: String? = "x"
        val store =
            JamSessionStore(
                object : JamStore {
                    override fun read(): String? = stored

                    override fun write(text: String?) {
                        stored = text
                    }
                },
            )
        store.save(JamSession("7k3m9q2x", "s", "u", null, emptyList()))
        assertEquals("7k3m9q2x", store.load()?.roomId)
        store.clear()
        assertNull(store.load())
    }
}
