package io.github.kickoman.qiyaa.yandex

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object TrackUrl {
    private const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"

    fun parseDownloadVariants(result: JsonElement): List<DownloadVariant> {
        val items = result as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.string("downloadInfoUrl")
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return@mapNotNull null
            DownloadVariant(
                codec = item.string("codec"),
                bitrateKbps = item.int("bitrateInKbps", 0),
                preview = item.boolean("preview", false),
                downloadInfoUrl = url,
            )
        }
    }

    fun pickBestVariant(variants: List<DownloadVariant>): DownloadVariant? {
        var best: DownloadVariant? = null
        for (variant in variants) {
            if (variant.codec != "mp3" || variant.preview) continue
            if (best == null || variant.bitrateKbps > best.bitrateKbps) best = variant
        }
        return best ?: variants.firstOrNull()
    }

    fun parseDownloadInfo(json: String): DownloadInfo? {
        val item = parseJsonObjectOrNull(json) ?: return null
        val info =
            DownloadInfo(
                host = item.scalarString("host"),
                path = item.scalarString("path"),
                timestamp = item.scalarString("ts"),
                secret = item.scalarString("s"),
            )
        if (info.host.isEmpty() || !info.path.startsWith("/") || info.secret.isEmpty()) return null
        return info
    }

    fun buildTrackUrl(info: DownloadInfo): String {
        val toSign = SIGN_SALT + info.path.substring(1) + info.secret
        val digest = MessageDigest.getInstance("MD5").digest(toSign.toByteArray(Charsets.UTF_8))
        val signature = digest.joinToString("") { "%02x".format(it) }
        return "https://${info.host}/get-mp3/$signature/${info.timestamp}${info.path}"
    }
}
