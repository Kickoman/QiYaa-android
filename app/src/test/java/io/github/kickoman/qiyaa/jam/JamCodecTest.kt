package io.github.kickoman.qiyaa.jam

import io.github.kickoman.qiyaa.support.Spec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JamCodecTest {
    private val examples = File(Spec.root, "jam/protocol/examples")

    @Test
    fun `every example of spec jam protocol parses when valid and is refused when invalid`() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (directory in examples.listFiles().orEmpty().filter(File::isDirectory).sortedBy(File::getName)) {
            val type = directory.name
            val client = type in JamCodec.CLIENT_TYPES
            assertTrue("$type is neither a client nor a server type", client || type in JamCodec.SERVER_TYPES)
            for (file in directory.listFiles().orEmpty().sortedBy(File::getName)) {
                checked++
                val decoded: Decoded<*> =
                    if (client) {
                        JamCodec.decodeClient(
                            file.readText(),
                        )
                    } else {
                        JamCodec.decodeServer(file.readText())
                    }
                val valid = !file.name.startsWith("invalid-")
                val parsed = decoded is Decoded.Message<*>
                if (parsed !=
                    valid
                ) {
                    failures +=
                        "$type/${file.name}: expected ${if (valid) "valid" else "invalid"}, got $decoded"
                }
            }
        }
        assertTrue("no examples found in $examples", checked > 90)
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `valid client examples survive encode and decode unchanged`() {
        for (type in JamCodec.CLIENT_TYPES) {
            val files = File(examples, type).listFiles().orEmpty().filterNot {
                it.name.startsWith("invalid-")
            }
            for (file in files) {
                val first = (JamCodec.decodeClient(file.readText()) as Decoded.Message).message
                val again = JamCodec.decodeClient(JamCodec.encode(first))
                assertEquals("$type/${file.name}", Decoded.Message(first), again)
            }
        }
    }

    @Test
    fun `resume without a snapshot is sent with an explicit null`() {
        val text = JamCodec.encode(
            Resume("h2", "7k3m9q2x", "H".repeat(43), "qjk_" + "K".repeat(43), outbox = emptyList()),
        )
        assertTrue(text, text.contains("\"snapshot\":null"))
        assertTrue(text, text.contains("\"outbox\":[]"))
    }

    @Test
    fun `unknown types and reasons are recognised and unknown fields are ignored`() {
        assertEquals(Decoded.UnknownType, JamCodec.decodeServer("""{"type":"dance"}"""))
        assertEquals(
            Decoded.Message(Ack("h1")),
            JamCodec.decodeServer("""{"type":"ack","id":"h1","later":true}"""),
        )
        val unknown = JamCodec.decodeServer("""{"type":"rejected","id":"h1","reason":"too-loud"}""")
        assertTrue(JamCodec.isUnknownReason(unknown))
        assertTrue(JamCodec.decodeServer("not json") is Decoded.Invalid)
    }
}
