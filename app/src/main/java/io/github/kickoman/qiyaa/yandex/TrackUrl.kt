package io.github.kickoman.qiyaa.yandex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/** Port of src/yandex/TrackUrl.cpp: signing of the mp3 download link. */
object TrackUrl {
    private const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"

    fun parseDownloadVariants(result: JsonElement): List<DownloadVariant> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapNotNull { v ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val url = o.str("downloadInfoUrl")
            if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return@mapNotNull null
            DownloadVariant(
                codec = o.str("codec"),
                bitrateKbps = (o["bitrateInKbps"] as? JsonPrimitive)?.intOrNull ?: 0,
                preview = (o["preview"] as? JsonPrimitive)?.booleanOrNull ?: false,
                downloadInfoUrl = url,
            )
        }
    }

    /** Full (non-preview) mp3 with the highest bitrate; otherwise the first variant. */
    fun pickBestVariant(variants: List<DownloadVariant>): DownloadVariant? {
        var best: DownloadVariant? = null
        for (v in variants) {
            if (v.codec != "mp3" || v.preview) continue
            if (best == null || v.bitrateKbps > best.bitrateKbps) best = v
        }
        return best ?: variants.firstOrNull()
    }

    fun parseDownloadInfo(json: String): DownloadInfo? {
        val o = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val info = DownloadInfo(host = o.str("host"), path = o.str("path"), ts = o.str("ts"), s = o.str("s"))
        if (info.host.isEmpty() || !info.path.startsWith("/") || info.s.isEmpty()) return null
        return info
    }

    fun buildTrackUrl(info: DownloadInfo): String {
        val toSign = SIGN_SALT + info.path.substring(1) + info.s
        val md5 = MessageDigest.getInstance("MD5").digest(toSign.toByteArray(Charsets.UTF_8))
        val sign = md5.joinToString("") { "%02x".format(it) }
        return "https://${info.host}/get-mp3/$sign/${info.ts}${info.path}"
    }

    private fun JsonObject.str(key: String): String = idString(this[key])
}

/** JSON numbers become their decimal string, strings pass through, anything else is "". */
fun idString(v: JsonElement?): String {
    val p = v as? JsonPrimitive ?: return ""
    if (p.isString) return p.content
    val c = p.content
    if (c == "null" || c == "true" || c == "false") return ""
    return c.toDoubleOrNull()?.let { d -> if (d == Math.floor(d) && !c.contains('e', true)) d.toLong().toString() else c } ?: c
}
