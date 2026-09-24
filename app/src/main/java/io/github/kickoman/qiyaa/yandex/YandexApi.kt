package io.github.kickoman.qiyaa.yandex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Port of src/yandex/ApiClient.cpp: the low-level Yandex Music client.
 * Every call unwraps the `{invocationInfo, result}` envelope and throws [ApiException] on errors.
 */
class YandexApi(
    private val client: OkHttpClient,
    val baseUrl: String = "https://api.music.yandex.net",
) {
    @Volatile
    var token: String = ""

    // ------------------------------------------------------------------ transport

    private fun request(url: HttpUrl): Request.Builder {
        val b = Request.Builder().url(url).header("Accept-Language", "ru")
        val t = token
        if (t.isNotEmpty()) b.header("Authorization", "OAuth $t")
        return b
    }

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = (baseUrl + path).toHttpUrl().newBuilder()
        for ((k, v) in query) b.addQueryParameter(k, v)
        return b.build()
    }

    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        execute(request(url(path, query)).get().build())

    suspend fun postForm(path: String, form: List<Pair<String, String>>): JsonElement {
        val body = FormBody.Builder()
        for ((k, v) in form) body.add(k, v)
        return execute(request(url(path)).post(body.build()).build())
    }

    suspend fun postJson(path: String, body: JsonObject): JsonElement =
        execute(request(url(path)).post(body.toString().toRequestBody(JSON_TYPE)).build())

    private suspend fun execute(req: Request): JsonElement = withContext(Dispatchers.IO) {
        val (status, body, reason) = try {
            client.newCall(req).execute().use { r -> Triple(r.code, r.body?.string().orEmpty(), r.message) }
        } catch (e: java.io.IOException) {
            throw ApiException("HTTP 0: ${e.message ?: e.javaClass.simpleName}")
        }
        val obj = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        if (status >= 400) {
            var msg = (obj?.get("error") as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (msg.isEmpty()) msg = (obj?.get("error") as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (msg.isEmpty()) msg = reason.ifEmpty { "error" }
            throw ApiException("HTTP $status: $msg")
        }
        obj?.get("result") ?: throw ApiException("unexpected response")
    }

    /** Raw GET that returns the body as text (used for the download-info hop). */
    suspend fun getText(fullUrl: String): String = withContext(Dispatchers.IO) {
        val req = request(fullUrl.toHttpUrl()).get().build()
        try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ApiException("download-info: HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        } catch (e: java.io.IOException) {
            if (e is ApiException) throw e
            throw ApiException("download-info: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ endpoints

    suspend fun accountStatus(): Account {
        val a = getJson("/account/status").obj["account"].obj
        val uid = idString(a["uid"])
        val login = a.str("login")
        val acc = Account(uid = uid, login = login, displayName = a.str("displayName").ifEmpty { login })
        if (uid.isEmpty()) throw ApiException("not authorized (no uid) — token expired?")
        return acc
    }

    suspend fun tracks(ids: List<String>): List<Track> {
        val r = postForm("/tracks/", listOf("track-ids" to ids.joinToString(","), "with-positions" to "false"))
        return (r as? JsonArray)?.map { parseTrack(it) } ?: emptyList()
    }

    suspend fun resolveTrackUrl(trackId: String): ResolvedUrl {
        val id = trackId.substringBefore(':')
        val variants = TrackUrl.parseDownloadVariants(getJson("/tracks/$id/download-info"))
        val best = TrackUrl.pickBestVariant(variants) ?: throw ApiException("no download variants")
        val infoUrl = best.downloadInfoUrl.toHttpUrl().newBuilder().addQueryParameter("format", "json").build().toString()
        val info = TrackUrl.parseDownloadInfo(getText(infoUrl)) ?: throw ApiException("download-info: unexpected response")
        return ResolvedUrl(TrackUrl.buildTrackUrl(info), best.bitrateKbps)
    }

    /** Fire-and-forget listen mark, sent once when a track starts (as the desktop app does). */
    suspend fun reportPlayStarted(account: Account, track: Track, playId: String) {
        val now = ISO_MS.format(Instant.now().atOffset(ZoneOffset.UTC))
        postForm(
            "/play-audio",
            listOf(
                "track-id" to track.id,
                "album-id" to track.albumId,
                "from" to "web-own_tracks-track-track-main",
                "play-id" to playId,
                "uid" to account.uid,
                "timestamp" to now,
                "client-now" to now,
                "track-length-seconds" to formatSeconds(track.durationMs),
                "total-played-seconds" to "0",
                "end-position-seconds" to "0",
            ),
        )
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val ISO_MS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        /** Qt's QString::number(double) rendering: integral values without ".0". */
        fun formatSeconds(durationMs: Long): String {
            val s = durationMs / 1000.0
            return if (s == Math.floor(s)) s.toLong().toString() else s.toString()
        }

        fun parseTrack(v: JsonElement): Track {
            val o = v.obj
            var title = o.str("title")
            val version = o.str("version")
            if (version.isNotEmpty()) title += " ($version)"
            val artists = (o["artists"] as? JsonArray)?.map { it.obj.str("name") } ?: emptyList()
            val albums = o["albums"] as? JsonArray
            val firstAlbum = albums?.firstOrNull()?.obj
            val cover = o.str("coverUri").ifEmpty { firstAlbum?.str("coverUri").orEmpty() }
            return Track(
                id = idString(o["id"]),
                title = title,
                artists = artists,
                albumId = firstAlbum?.let { idString(it["id"]) }.orEmpty(),
                durationMs = ((o["durationMs"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).toLong(),
                available = (o["available"] as? JsonPrimitive)?.booleanOrNull ?: true,
                coverUrl = coverUrl(cover),
            )
        }

        fun coverUrl(uri: String, size: String = "400x400"): String? {
            if (uri.isEmpty()) return null
            val withSize = uri.replace("%%", size)
            return if (withSize.startsWith("http")) withSize else "https://$withSize"
        }

        /** Some endpoints wrap tracks: {"track": {...}} or {"id":..., "track": {...}}. */
        fun parseTrackArray(arr: JsonElement?): List<Track> {
            val a = arr as? JsonArray ?: return emptyList()
            return a.map { v ->
                val inner = (v as? JsonObject)?.get("track")
                parseTrack(if (inner is JsonObject) inner else v)
            }
        }
    }
}

internal val JsonElement?.obj: JsonObject get() = this as? JsonObject ?: JsonObject(emptyMap())
internal val JsonElement?.arr: JsonArray get() = this as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
