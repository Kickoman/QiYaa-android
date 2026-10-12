package io.github.kickoman.qiyaa.yandex

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class Quality { STANDARD, ECONOMY }

data class FileInfoRequest(
    val trackId: String,
    val quality: String,
    val codecs: List<String>,
    val transport: String,
)

data class FileDownloadInfo(
    val quality: String,
    val codec: String,
    val bitrateKbps: Int,
    val transport: String,
    val url: String,
    val urls: List<String>,
    val key: String?,
)

object FileInfo {
    const val PATH = "/get-file-info"

    /** The `X-Yandex-Music-Client` the signature is checked against. */
    const val CLIENT = "YandexMusicDesktopAppWindows/5.95.0"
    const val CLIENT_HEADER = "X-Yandex-Music-Client"

    /** Yandex Music's Windows app's key: its Android app's is refused since 2026-10. */
    private const val SIGN_KEY = "kzqU4XhfCaY6B6JTHODeq5"

    fun economy(trackId: String): FileInfoRequest =
        FileInfoRequest(trackId, "lq", listOf("he-aac-mp4", "mp3"), "raw")

    fun sign(request: FileInfoRequest, timestampSeconds: Long): String {
        val message =
            "$timestampSeconds${request.trackId}${request.quality}" +
                "${request.codecs.joinToString("")}${request.transport}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(
            mac.doFinal(message.toByteArray(Charsets.UTF_8)),
        ).dropLast(1)
    }

    /** The query in its order, signature included; values not yet percent-encoded. */
    fun query(request: FileInfoRequest, timestampSeconds: Long): List<Pair<String, String>> = listOf(
        "ts" to timestampSeconds.toString(),
        "trackId" to request.trackId,
        "quality" to request.quality,
        "codecs" to request.codecs.joinToString(","),
        "transports" to request.transport,
        "sign" to sign(request, timestampSeconds),
    )

    /** The reply's `result`; null without an https `url`. */
    fun parse(result: JsonElement?): FileDownloadInfo? {
        val item = result.objectOrEmpty["downloadInfo"].objectOrEmpty
        val url = item.string("url")
        if (!url.startsWith("https://") || url.length <= "https://".length) return null
        return FileDownloadInfo(
            quality = item.string("quality"),
            codec = item.string("codec"),
            bitrateKbps = item.int("bitrate", 0),
            transport = item.string("transport"),
            url = url,
            urls = item["urls"].arrayOrEmpty.mapNotNull { (it as? JsonPrimitive)?.content },
            key = item.string("key").ifEmpty { null },
        )
    }

    fun isUsable(info: FileDownloadInfo, request: FileInfoRequest): Boolean =
        info.transport == "raw" && info.codec in request.codecs
}
