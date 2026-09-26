package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackUrlTest {
    @Test
    fun `buildTrackUrl signs the path like Yaamp`() {
        val info =
            DownloadInfo("s123vla.storage.yandex.net", "/rmusic/U2FsdGVk/abc", "000612a3b4c5d", "deadbeef")
        // md5("XGRlBW9FXlekgbPrRHuSiA" + "rmusic/U2FsdGVk/abc" + "deadbeef")
        assertEquals(
            "https://s123vla.storage.yandex.net/get-mp3/5c38e49c01f9a959428986790af6f9ca/000612a3b4c5d/rmusic/U2FsdGVk/abc",
            TrackUrl.buildTrackUrl(info),
        )
    }

    @Test
    fun `pickBestVariant prefers the full mp3 with the highest bitrate`() {
        val variants =
            Json.parseToJsonElement(
                """[
                {"codec":"mp3","bitrateInKbps":320,"preview":true,"downloadInfoUrl":"https://x/a?sign=1"},
                {"codec":"aac","bitrateInKbps":256,"preview":false,"downloadInfoUrl":"https://x/b?sign=1"},
                {"codec":"mp3","bitrateInKbps":192,"preview":false,"downloadInfoUrl":"https://x/c?sign=1"},
                {"codec":"mp3","bitrateInKbps":320,"preview":false,"downloadInfoUrl":"https://x/d?sign=1"}
                ]""",
            )
        val best = TrackUrl.pickBestVariant(TrackUrl.parseDownloadVariants(variants))
        assertNotNull(best)
        assertEquals(320, best!!.bitrateKbps)
        assertEquals("https://x/d?sign=1", best.downloadInfoUrl)
    }

    @Test
    fun `pickBestVariant falls back to the first variant and to null`() {
        val variants =
            Json.parseToJsonElement(
                """[{"codec":"aac","bitrateInKbps":64,"preview":false,"downloadInfoUrl":"https://x/a"}]""",
            )
        assertEquals(
            "https://x/a",
            TrackUrl.pickBestVariant(TrackUrl.parseDownloadVariants(variants))!!.downloadInfoUrl,
        )
        assertNull(TrackUrl.pickBestVariant(emptyList()))
    }

    @Test
    fun `parseDownloadInfo accepts numeric ts and refuses malformed bodies`() {
        val info = TrackUrl.parseDownloadInfo(
            """{"s":"abc","ts":"0005","path":"/p/q","host":"h.net","regional-host":[]}""",
        )
        assertNotNull(info)
        assertEquals("h.net", info!!.host)
        assertEquals("0005", info.timestamp)
        assertNull(TrackUrl.parseDownloadInfo("<xml/>"))
        assertNull(TrackUrl.parseDownloadInfo("""{"s":"abc","ts":"1","path":"noslash","host":"h"}"""))
        assertEquals(
            "5",
            TrackUrl.parseDownloadInfo("""{"s":"abc","ts":5,"path":"/p","host":"h"}""")!!.timestamp,
        )
    }

    @Test
    fun `idString renders numbers and strings and drops null and booleans`() {
        assertEquals("777", idString(Json.parseToJsonElement("777")))
        assertEquals("12345", idString(Json.parseToJsonElement("\"12345\"")))
        assertEquals("", idString(null))
        assertEquals("", idString(Json.parseToJsonElement("null")))
        assertEquals("", idString(Json.parseToJsonElement("true")))
        assertEquals("1.5", idString(Json.parseToJsonElement("1.5")))
    }
}
