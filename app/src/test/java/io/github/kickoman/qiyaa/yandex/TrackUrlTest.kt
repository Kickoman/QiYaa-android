package io.github.kickoman.qiyaa.yandex

import io.github.kickoman.qiyaa.support.Spec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackUrlTest {
    @Test
    fun `download variants and the chosen one match the spec for every case`() {
        for (case in Spec.cases("tracks-download-info")) {
            val variants = TrackUrl.parseDownloadVariants(Spec.fixture("tracks-download-info", case).result())
            val actual =
                buildJsonObject {
                    put(
                        "variants",
                        buildJsonArray {
                            for (variant in variants) {
                                add(
                                    buildJsonObject {
                                        put("codec", variant.codec)
                                        put("bitrateKbps", variant.bitrateKbps)
                                        put("preview", variant.preview)
                                        put("downloadInfoUrl", variant.downloadInfoUrl)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "best",
                        TrackUrl.pickBestVariant(variants)?.downloadInfoUrl?.let(::JsonPrimitive) ?: JsonNull,
                    )
                }
            assertEquals("tracks-download-info/$case", Spec.expected("tracks-download-info", case), actual)
        }
    }

    @Test
    fun `storage download info and the signed link match the spec for every case`() {
        for (case in Spec.cases("storage-download-info")) {
            val info = TrackUrl.parseDownloadInfo(Spec.fixture("storage-download-info", case).body)
            val actual: JsonObject =
                if (info == null) {
                    buildJsonObject { put("invalid", true) }
                } else {
                    buildJsonObject {
                        put("host", info.host)
                        put("path", info.path)
                        put("ts", info.timestamp)
                        put("s", info.secret)
                        put("trackUrl", TrackUrl.buildTrackUrl(info))
                    }
                }
            assertEquals("storage-download-info/$case", Spec.expected("storage-download-info", case), actual)
        }
    }

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
    fun `idString renders numbers and strings and drops null and booleans`() {
        assertEquals("777", idString(Json.parseToJsonElement("777")))
        assertEquals("12345", idString(Json.parseToJsonElement("\"12345\"")))
        assertEquals("", idString(null))
        assertEquals("", idString(Json.parseToJsonElement("null")))
        assertEquals("", idString(Json.parseToJsonElement("true")))
        assertEquals("1.5", idString(Json.parseToJsonElement("1.5")))
    }
}
