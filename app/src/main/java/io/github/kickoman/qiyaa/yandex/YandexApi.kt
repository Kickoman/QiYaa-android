package io.github.kickoman.qiyaa.yandex

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class YandexApi(private val client: OkHttpClient, val baseUrl: String = "https://api.music.yandex.net") {
    @Volatile
    var token: String = ""

    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        execute("GET", path, request(url(path, query)).get().build())

    suspend fun postForm(path: String, form: List<Pair<String, String>>): JsonElement {
        val body = FormBody.Builder()
        for ((key, value) in form) body.add(key, value)
        return execute("POST", path, request(url(path)).post(body.build()).build())
    }

    suspend fun postJson(path: String, body: JsonObject): JsonElement =
        execute("POST", path, request(url(path)).post(body.toString().toRequestBody(JSON_TYPE)).build())

    suspend fun getText(fullUrl: String): String = withContext(Dispatchers.IO) {
        val request = request(fullUrl.toHttpUrl()).get().build()
        val response =
            try {
                client.newCall(request).execute()
            } catch (failed: IOException) {
                throw NetworkException("GET", fullUrl, failed)
            }
        response.use {
            if (!it.isSuccessful) {
                throw HttpException(
                    it.code,
                    "GET",
                    fullUrl,
                    it.message.ifEmpty {
                        "error"
                    },
                )
            }
            it.body?.string().orEmpty()
        }
    }

    suspend fun accountStatus(): Account {
        val account = getJson("/account/status").objectOrEmpty["account"].objectOrEmpty
        val uid = idString(account["uid"])
        if (uid.isEmpty()) {
            throw AuthException(
                "GET /account/status returned no uid: the token was not accepted",
            )
        }
        val login = account.string("login")
        return Account(
            uid = uid,
            login = login,
            displayName = account.string("displayName").ifEmpty {
                login
            },
        )
    }

    suspend fun tracks(ids: List<String>): List<Track> {
        val form = listOf("track-ids" to ids.joinToString(","), "with-positions" to "false")
        val result = postForm("/tracks/", form)
        return (result as? JsonArray)?.map(TrackParsing::parseTrack) ?: emptyList()
    }

    suspend fun resolveTrackUrl(trackId: String): ResolvedUrl {
        val id = trackId.substringBefore(':')
        val path = "/tracks/$id/download-info"
        val variants = TrackUrl.parseDownloadVariants(getJson(path))
        val best =
            TrackUrl.pickBestVariant(variants)
                ?: throw MalformedResponseException("GET", path, "no download variants")
        val infoUrl =
            best.downloadInfoUrl.toHttpUrl().newBuilder().addQueryParameter(
                "format",
                "json",
            ).build().toString()
        val info =
            TrackUrl.parseDownloadInfo(getText(infoUrl))
                ?: throw MalformedResponseException("GET", infoUrl, "download info without host, path or s")
        return ResolvedUrl(TrackUrl.buildTrackUrl(info), best.bitrateKbps)
    }

    suspend fun reportPlayStarted(account: Account, track: Track, playId: String) {
        val now = ISO_MILLIS.format(Instant.now().atOffset(ZoneOffset.UTC))
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

    private fun request(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept-Language", "ru")
        val currentToken = token
        if (currentToken.isNotEmpty()) builder.header("Authorization", "OAuth $currentToken")
        return builder
    }

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = (baseUrl + path).toHttpUrl().newBuilder()
        for ((key, value) in query) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private suspend fun execute(method: String, path: String, request: Request): JsonElement =
        withContext(Dispatchers.IO) {
            val (status, body, reason) =
                try {
                    client.newCall(request).execute().use { response ->
                        Triple(response.code, response.body?.string().orEmpty(), response.message)
                    }
                } catch (failed: IOException) {
                    throw NetworkException(method, path, failed)
                }
            val json = parseJsonObjectOrNull(body)
            if (status >= 400) throw HttpException(status, method, path, errorMessage(json, reason))
            json?.get("result")
                ?: throw MalformedResponseException(method, path, "no \"result\" in the response")
        }

    private fun errorMessage(json: JsonObject?, reason: String): String {
        val message =
            when (val error = json?.get("error")) {
                is JsonObject -> error.string("message")
                is JsonPrimitive -> error.contentOrNull.orEmpty()
                else -> ""
            }
        return message.ifEmpty { reason.ifEmpty { "error" } }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        )

        fun formatSeconds(durationMs: Long): String {
            val seconds = durationMs / 1000.0
            return if (seconds == Math.floor(seconds)) seconds.toLong().toString() else seconds.toString()
        }
    }
}
