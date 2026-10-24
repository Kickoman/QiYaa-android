package io.github.kickoman.qiyaa.jam

import io.github.kickoman.qiyaa.support.Spec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JamLinkTest {
    @Test
    fun `GUEST-01 the default server's link gives its server, room and secret`() {
        assertEquals(
            JamLink("https://qiyaa.kanstancin.net", ROOM, SECRET),
            JamLink.find("https://qiyaa.kanstancin.net/j/$ROOM#$SECRET"),
        )
    }

    @Test
    fun `a link's url is found as the same link, with or without its secret`() {
        val links =
            listOf(
                JamLink("https://jam.example.org:8443/party", ROOM, SECRET),
                JamLink("https://qiyaa.kanstancin.net", ROOM, null),
            )
        for (link in links) assertEquals(link, JamLink.find(link.url))
        assertEquals(
            "https://qiyaa.kanstancin.net/j/$ROOM#$SECRET",
            JamLink("https://qiyaa.kanstancin.net", ROOM, SECRET).url,
        )
    }

    @Test
    fun `GUEST-01 the first link is found inside the host's share text`() {
        val expected = JamLink("https://qiyaa.kanstancin.net", ROOM, SECRET)
        assertEquals(expected, JamLink.find("Join my jam: https://qiyaa.kanstancin.net/j/$ROOM#$SECRET"))
        assertEquals(expected, JamLink.find("\n\n  https://qiyaa.kanstancin.net/j/$ROOM#$SECRET  \nbye"))
        assertEquals(expected, JamLink.find("Come (https://qiyaa.kanstancin.net/j/$ROOM#$SECRET)."))
        assertEquals(expected, JamLink.find("https://qiyaa.kanstancin.net/j/$ROOM#$SECRET\u00A0— come!"))
        assertEquals(
            expected,
            JamLink.find(
                "https://example.org/about then https://qiyaa.kanstancin.net/j/$ROOM#$SECRET " +
                    "and https://example.org/j/zzzzzzzz#$SECRET",
            ),
        )
        assertEquals(expected, JamLink.find("https://qiyaa.kanstancin.net/j/$ROOM/?utm=x#$SECRET"))
    }

    @Test
    fun `GUEST-01 the server keeps its prefix and port, in lower case, without 443 and a trailing slash`() {
        assertEquals(
            "https://example.org/jam",
            JamLink.find("https://example.org/jam/j/$ROOM#$SECRET")?.serverUrl,
        )
        assertEquals(
            "https://example.org/Jam/x",
            JamLink.find("https://example.org/Jam/x//j/$ROOM#$SECRET")?.serverUrl,
        )
        assertEquals("https://example.org:8443", JamLink.find("https://example.org:8443/j/$ROOM")?.serverUrl)
        assertEquals("https://example.org", JamLink.find("https://example.org:443/j/$ROOM")?.serverUrl)
        assertEquals(
            JamLink("https://qiyaa.kanstancin.net", ROOM, SECRET),
            JamLink.find("HTTPS://Qiyaa.Kanstancin.NET:443/j/$ROOM#$SECRET"),
        )
        assertEquals("https://[::1]:8443", JamLink.find("https://[::1]:8443/j/$ROOM")?.serverUrl)
    }

    @Test
    fun `GUEST-05 a missing or broken secret counts as missing`() {
        val bare = JamLink("https://example.org", ROOM, null)
        assertEquals(bare, JamLink.find("https://example.org/j/$ROOM"))
        assertEquals(bare, JamLink.find("https://example.org/j/$ROOM#"))
        assertEquals(bare, JamLink.find("https://example.org/j/$ROOM#${SECRET.drop(1)}"))
        assertEquals(bare, JamLink.find("https://example.org/j/$ROOM#${SECRET}A"))
        assertEquals(bare, JamLink.find("https://example.org/j/$ROOM#$SECRET%20"))
    }

    @Test
    fun `GUEST-01 text without a jam link, another path, scheme or room shape is refused`() {
        for (text in listOf(
            "",
            "Join my jam!",
            "qiyaa.kanstancin.net/j/$ROOM#$SECRET",
            "http://qiyaa.kanstancin.net/j/$ROOM#$SECRET",
            "http://10.0.2.2:8090/j/$ROOM#$SECRET",
            "wss://qiyaa.kanstancin.net/j/$ROOM#$SECRET",
            "ftp://qiyaa.kanstancin.net/j/$ROOM#$SECRET",
            "https://qiyaa.kanstancin.net/j/7K3M9Q2X#$SECRET",
            "https://qiyaa.kanstancin.net/j/7k3m9q2#$SECRET",
            "https://qiyaa.kanstancin.net/j/7k3m9q2xx#$SECRET",
            "https://qiyaa.kanstancin.net/j/7k3m9q2u#$SECRET",
            "https://qiyaa.kanstancin.net/x/$ROOM#$SECRET",
            "https://qiyaa.kanstancin.net/j/$ROOM/more#$SECRET",
            "https://user@qiyaa.kanstancin.net/j/$ROOM#$SECRET",
        )) {
            assertNull(text, JamLink.find(text))
        }
    }

    @Test
    fun `GUEST-01 every join link of the protocol examples gives back its room and secret`() {
        val examples = File(Spec.root, "jam/protocol/examples")
        var checked = 0
        val files =
            listOf("created", "linkRotated")
                .flatMap { File(examples, it).listFiles().orEmpty().toList() }
                .filterNot { it.name.startsWith("invalid-") }
        for (file in files) {
            val message = (JamCodec.decodeServer(file.readText()) as Decoded.Message).message
            val (url, secret) =
                when (message) {
                    is Created -> message.joinUrl to message.joinSecret
                    is LinkRotated -> message.joinUrl to message.joinSecret
                    else -> error("${file.name} holds ${message::class.simpleName}")
                }
            val link = JamLink.find(url)
            assertEquals(file.name, ROOM, link?.roomId)
            assertEquals(file.name, secret, link?.joinSecret)
            assertEquals(file.name, url.substringBefore("/j/"), link?.serverUrl)
            checked++
        }
        assertTrue("no join links found in $examples", checked >= 3)
    }

    private companion object {
        const val ROOM = "7k3m9q2x"
        const val SECRET = "WDkyFgMr5iV3hKwManPvsg"
    }
}
