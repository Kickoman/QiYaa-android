package io.github.kickoman.qiyaa.jam

import io.github.kickoman.qiyaa.support.ECMASCRIPT_SPACES
import io.github.kickoman.qiyaa.support.Spec
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a name ending in any ECMAScript white space is refused, one ending in U+0085 passes`() {
        for (space in ECMASCRIPT_SPACES) {
            val label = "U+%04X".format(space.code)
            assertFalse(label, decodes(Create("h1", "Маша$space")))
            assertFalse(label, decodes(Create("h1", "${space}Маша")))
            assertEquals(label, space.code >= 0x20, decodes(Create("h1", "Ма${space}ша")))
        }
        assertTrue(decodes(Create("h1", "Маша\u0085")))
    }

    @Test
    fun `of all BMP code points a name refuses at its end exactly the controls and ECMAScript white space`() {
        val wrong = mutableListOf<String>()
        for (code in 0..0xFFFF) {
            if (code in 0xD800..0xDFFF) continue
            val char = code.toChar()
            val refused = code < 0x20 || code == 0x7F || char in ECMASCRIPT_SPACES
            if (decodes(Create("h1", "x$char")) == refused) wrong += "U+%04X".format(code)
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a cover template, a listen link and a join link with ECMAScript white space are refused`() {
        val track = JamTrack("1695498", "168137", "Что такое осень", listOf("ДДТ"), 297_700)
        val cover = "avatars.yandex.net/get-music-content/34131/73fed284.a.168137-2/%%"
        val joinUrl = "https://jam.example.org/party/j/7k3m9q2x#WDkyFgMr5iV3hKwManPvsg"
        assertTrue(decodes(Add("q1", track = track.copy(coverUri = cover))))
        assertTrue(JamCodec.isListenUrl(LISTEN_URL))
        assertTrue(decodes(created(joinUrl)))
        for (space in listOf("\u2028", "\u3000", "\uFEFF")) {
            val label = "U+%04X".format(space.single().code)
            assertFalse(label, decodes(Add("q1", track = track.copy(coverUri = cover + space))))
            assertFalse(label, JamCodec.isListenUrl("$LISTEN_URL$space"))
            assertFalse(label, decodes(created(joinUrl.replace("/party/", "/par${space}ty/"))))
        }
    }

    @Test
    fun `a search text of ECMAScript white space only is refused`() {
        assertFalse(decodes(Search("s1", "\uFEFF\uFEFF")))
        assertFalse(decodes(Search("s1", "\u00A0\u2028")))
        assertTrue(decodes(Search("s1", "\uFEFFДДТ")))
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
            Resume("h2", "7k3m9q2x", "H".repeat(43), outbox = emptyList()),
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

    @Test
    fun `a catalog track goes out with no source key`() {
        val track = JamTrack("38633712", "5307396", "Группа крови", listOf("КИНО"), 235_100)
        val text = JamCodec.encode(SearchResult("s12", tracks = listOf(track)))
        assertFalse(text, text.contains("source"))
    }

    @Test
    fun `a track with source yandex is a catalog track in a search result`() {
        val sent = example("searchResult/tracks.json").withFirst("tracks") { it.with("source", "yandex") }
        val track = (decodedClient(sent) as SearchResult).tracks!!.first()
        assertEquals(JamTrackSource.YANDEX, track.source)
        assertTrue(track.isCatalog)
    }

    @Test
    fun `a host track with an album or an unknown source is refused`() {
        val withAlbum = example("playing/host-track.json").withObject("track") {
            it.with("albumId", "5307396")
        }
        assertRefused(JamCodec.decodeClient(withAlbum.toString()), "has an albumId or a coverUri")
        val unknown = example("playing/host-track.json").withObject("track") { it.with("source", "local") }
        assertTrue(JamCodec.decodeClient(unknown.toString()) is Decoded.Invalid)
    }

    @Test
    fun `search results from the server hold only catalog tracks`() {
        val sent = example("searchResults/ok.json").withFirst("tracks") { HOST_TRACK }
        assertRefused(JamCodec.decodeServer(sent.toString()), "searchResults.tracks holds the host track")
    }

    @Test
    fun `the recent items of a state hold only catalog tracks`() {
        val sent =
            example("state/host-track.json").withObject("room") { room ->
                room.withFirst("recent") { item -> item.with("track", HOST_TRACK) }
            }
        assertRefused(JamCodec.decodeServer(sent.toString()), "recent[].track holds the host track")
    }

    @Test
    fun `LISTEN-14 a host track that plays now comes with no listenUrl`() {
        val sent = example("state/host-track.json").withNowPlaying { it.with("listenUrl", LISTEN_URL) }
        assertRefused(JamCodec.decodeServer(sent.toString()), "comes with a listenUrl or a listenNextUrl")
    }

    @Test
    fun `LISTEN-14 a host track that plays now comes with no listenNextUrl`() {
        val sent = example("state/host-track.json").withNowPlaying { it.with("listenNextUrl", LISTEN_URL) }
        assertRefused(JamCodec.decodeServer(sent.toString()), "comes with a listenUrl or a listenNextUrl")
    }

    @Test
    fun `ROOM-66 an add of a host track passes the codec, the server refuses it`() {
        val sent = example("add/track.json").with("track", HOST_TRACK)
        val add = decodedClient(sent) as Add
        assertFalse(add.track!!.isCatalog)
    }

    private fun decodes(message: ClientMessage): Boolean =
        JamCodec.decodeClient(JamCodec.encode(message)) is Decoded.Message

    private fun decodes(message: ServerMessage): Boolean =
        JamCodec.decodeServer(JamCodec.encodeServer(message)) is Decoded.Message

    private fun created(joinUrl: String): Created = Created(
        "h1",
        "7k3m9q2x",
        "R0CuY0ewFywBJU_1W65a_1GZ9ERuf21kPUAYWz9HUUU",
        "WDkyFgMr5iV3hKwManPvsg",
        joinUrl,
        "h7k2m9",
    )

    private fun example(path: String): JsonObject =
        JamCodec.json.parseToJsonElement(File(examples, path).readText()).jsonObject

    private fun decodedClient(message: JsonObject): ClientMessage {
        val decoded = JamCodec.decodeClient(message.toString())
        assertTrue("$decoded", decoded is Decoded.Message)
        return (decoded as Decoded.Message).message
    }

    private fun assertRefused(decoded: Decoded<*>, problem: String) {
        assertTrue("$decoded", decoded is Decoded.Invalid && decoded.problem.contains(problem))
    }

    private fun JsonObject.with(key: String, value: String): JsonObject = with(key, JsonPrimitive(value))

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(
        this + (key to value),
    )

    private fun JsonObject.withObject(key: String, change: (JsonObject) -> JsonObject): JsonObject =
        with(key, change(getValue(key).jsonObject))

    private fun JsonObject.withFirst(key: String, change: (JsonObject) -> JsonObject): JsonObject {
        val list = getValue(key).jsonArray
        return with(key, JsonArray(listOf(change(list.first().jsonObject)) + list.drop(1)))
    }

    private fun JsonObject.withNowPlaying(change: (JsonObject) -> JsonObject): JsonObject =
        withObject("room") { room -> room.withObject("nowPlaying", change) }

    private companion object {
        val HOST_TRACK: JsonObject =
            JamCodec.json.parseToJsonElement(
                """{"id":"f3a9c2e17b4d5a60","source":"host","title":"Купалінка","artists":["Наш гурт"],""" +
                    """"durationMs":214000}""",
            ).jsonObject
        const val LISTEN_URL =
            "https://s963sas.storage.yandex.net/get-mp3/0123456789abcdef0123456789abcdef/00065cd937b03427/" +
                "rmusic/U2FsdGVkX19example/track.mp3"
    }
}
