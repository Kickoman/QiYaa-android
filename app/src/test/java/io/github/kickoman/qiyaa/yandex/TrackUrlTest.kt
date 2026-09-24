package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrackUrlTest {
    @Test
    fun signsTrackUrlLikeYaamp() {
        val info = DownloadInfo("s123vla.storage.yandex.net", "/rmusic/U2FsdGVk/abc", "000612a3b4c5d", "deadbeef")
        // md5("XGRlBW9FXlekgbPrRHuSiA" + "rmusic/U2FsdGVk/abc" + "deadbeef")
        assertEquals(
            "https://s123vla.storage.yandex.net/get-mp3/5c38e49c01f9a959428986790af6f9ca/000612a3b4c5d/rmusic/U2FsdGVk/abc",
            TrackUrl.buildTrackUrl(info),
        )
    }

    @Test
    fun picksBestFullMp3() {
        val arr = Json.parseToJsonElement(
            """[
            {"codec":"mp3","bitrateInKbps":320,"preview":true,"downloadInfoUrl":"https://x/a?sign=1"},
            {"codec":"aac","bitrateInKbps":256,"preview":false,"downloadInfoUrl":"https://x/b?sign=1"},
            {"codec":"mp3","bitrateInKbps":192,"preview":false,"downloadInfoUrl":"https://x/c?sign=1"},
            {"codec":"mp3","bitrateInKbps":320,"preview":false,"downloadInfoUrl":"https://x/d?sign=1"}
            ]""",
        )
        val best = TrackUrl.pickBestVariant(TrackUrl.parseDownloadVariants(arr))
        assertNotNull(best)
        assertEquals(320, best!!.bitrateKbps)
        assertEquals("https://x/d?sign=1", best.downloadInfoUrl)
    }

    @Test
    fun fallsBackToFirstVariant() {
        val arr = Json.parseToJsonElement("""[{"codec":"aac","bitrateInKbps":64,"preview":false,"downloadInfoUrl":"https://x/a"}]""")
        assertEquals("https://x/a", TrackUrl.pickBestVariant(TrackUrl.parseDownloadVariants(arr))!!.downloadInfoUrl)
        assertNull(TrackUrl.pickBestVariant(emptyList()))
    }

    @Test
    fun parsesDownloadInfoJson() {
        val info = TrackUrl.parseDownloadInfo("""{"s":"abc","ts":"0005","path":"/p/q","host":"h.net","regional-host":[]}""")
        assertNotNull(info)
        assertEquals("h.net", info!!.host)
        assertEquals("0005", info.ts)
        assertNull(TrackUrl.parseDownloadInfo("<xml/>"))
        assertNull(TrackUrl.parseDownloadInfo("""{"s":"abc","ts":"1","path":"noslash","host":"h"}"""))
        // Numeric ts is accepted.
        assertEquals("5", TrackUrl.parseDownloadInfo("""{"s":"abc","ts":5,"path":"/p","host":"h"}""")!!.ts)
    }

    @Test
    fun idStringHandlesNumbersAndStrings() {
        assertEquals("777", idString(Json.parseToJsonElement("777")))
        assertEquals("12345", idString(Json.parseToJsonElement("\"12345\"")))
        assertEquals("", idString(null))
        assertEquals("", idString(Json.parseToJsonElement("null")))
    }
}
