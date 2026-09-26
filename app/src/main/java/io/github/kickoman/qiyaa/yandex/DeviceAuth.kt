package io.github.kickoman.qiyaa.yandex

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class DeviceAuth(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://oauth.yandex.ru",
    private val deviceName: String = "QiYaa",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Code(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalMs: Long,
        val deadlineMs: Long,
    )

    suspend fun requestCode(): Code {
        val response = post(
            "/device/code",
            listOf(
                "client_id" to CLIENT_ID,
                "device_name" to "QiYaa ($deviceName)",
            ),
        )
        val deviceCode = response.string("device_code")
        val userCode = response.string("user_code")
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            throw AuthException(errorDescription(response, "POST /device/code returned no device_code"))
        }
        val verificationUrl =
            response.string("verification_url").takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
                ?: DEFAULT_VERIFICATION_URL
        val intervalSeconds = maxOf(1, response.int("interval", DEFAULT_INTERVAL_SECONDS))
        val expiresInSeconds = response.int("expires_in", DEFAULT_EXPIRES_IN_SECONDS)
        return Code(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl,
            intervalMs = intervalSeconds * 1000L,
            deadlineMs = clock() + expiresInSeconds * 1000L,
        )
    }

    suspend fun waitForToken(code: Code): String {
        var intervalMs = code.intervalMs
        while (true) {
            delay(intervalMs)
            if (clock() > code.deadlineMs) throw AuthException("Code expired, start again")
            val response =
                post(
                    "/token",
                    listOf(
                        "grant_type" to "device_code",
                        "code" to code.deviceCode,
                        "client_id" to CLIENT_ID,
                        "client_secret" to CLIENT_SECRET,
                    ),
                )
            val token = response.string("access_token")
            if (token.isNotEmpty()) return token
            when (val error = response.string("error")) {
                "authorization_pending" -> {}
                "slow_down" -> intervalMs += SLOW_DOWN_STEP_MS
                else -> throw AuthException(errorDescription(response, error.ifEmpty { "sign-in failed" }))
            }
        }
    }

    private suspend fun post(path: String, form: List<Pair<String, String>>): JsonObject =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
            for ((key, value) in form) body.add(key, value)
            val request = Request.Builder().url(baseUrl + path).post(body.build()).build()
            val text =
                try {
                    client.newCall(request).execute().use { it.body?.string().orEmpty() }
                } catch (failed: IOException) {
                    throw AuthException("POST $path failed: ${failed.message ?: failed.javaClass.simpleName}")
                }
            parseJsonObjectOrNull(text) ?: JsonObject(emptyMap())
        }

    private fun errorDescription(response: JsonObject, fallback: String): String =
        response.string("error_description").ifEmpty { response.string("error").ifEmpty { fallback } }

    companion object {
        const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"
        const val BROWSER_LOGIN_URL =
            "https://oauth.yandex.ru/authorize?response_type=token&client_id=$CLIENT_ID"
        const val DEFAULT_VERIFICATION_URL = "https://ya.ru/device"
        const val DEFAULT_INTERVAL_SECONDS = 5
        const val DEFAULT_EXPIRES_IN_SECONDS = 300
        const val SLOW_DOWN_STEP_MS = 2_000L
    }
}
