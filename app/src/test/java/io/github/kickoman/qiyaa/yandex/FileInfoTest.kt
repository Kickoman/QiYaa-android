package io.github.kickoman.qiyaa.yandex

import io.github.kickoman.qiyaa.support.Spec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileInfoTest {
    @Test
    fun `get-file-info replies parse to the spec's file or fall back for every case`() {
        for (case in Spec.cases("get-file-info")) {
            val fixture = Spec.fixture("get-file-info", case)
            val info =
                if (fixture.status ==
                    200
                ) {
                    FileInfo.parse(parseJsonObjectOrNull(fixture.body)?.get("result"))
                } else {
                    null
                }
            val actual: JsonObject =
                if (info == null) {
                    buildJsonObject { put("fallback", true) }
                } else {
                    buildJsonObject {
                        put("quality", info.quality)
                        put("codec", info.codec)
                        put("bitrateKbps", info.bitrateKbps)
                        put("transport", info.transport)
                        put("url", info.url)
                        put("urls", buildJsonArray { info.urls.forEach { add(JsonPrimitive(it)) } })
                        put("key", info.key?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
            assertEquals("get-file-info/$case", Spec.expected("get-file-info", case), actual)
        }
    }

    @Test
    fun `the signature matches the worked example of the spec`() {
        // spec/player/quality.md, "Worked example".
        assertEquals(
            "keefcyb+nfzl1cRn8glR+ZyzpQf/h2gSrMF0fHIOHpQ",
            FileInfo.sign(FileInfo.economy("155647609"), 1_760_000_000),
        )
        assertEquals(
            listOf("ts", "trackId", "quality", "codecs", "transports", "sign"),
            FileInfo.query(FileInfo.economy("1"), 1).map { it.first },
        )
        assertEquals("he-aac-mp4,mp3", FileInfo.query(FileInfo.economy("1"), 1)[3].second)
    }

    @Test
    fun `only a plain file in a codec that was asked for is usable`() {
        val request = FileInfo.economy("1")
        val info = FileDownloadInfo("lq", "he-aac-mp4", 64, "raw", "https://strm/x", emptyList(), null)
        assertTrue(FileInfo.isUsable(info, request))
        assertFalse(FileInfo.isUsable(info.copy(transport = "encraw", key = "00"), request))
        assertFalse(FileInfo.isUsable(info.copy(codec = "flac-mp4"), request))
    }
}
