package io.github.kickoman.qiyaa.yandex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Port of src/yandex/OAuth.cpp: Yandex OAuth "device code" flow with the public
 * Yandex Music client (the one Yaamp and yandex-music-api use).
 */
class DeviceAuth(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://oauth.yandex.ru",
    private val deviceName: String = "QiYaa",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Code(val deviceCode: String, val userCode: String, val verificationUrl: String, val intervalMs: Long, val deadlineMs: Long)

    class AuthException(message: String) : Exception(message)

    /** Step 1: ask for a code the user types at ya.ru/device. */
    suspend fun requestCode(): Code {
        val o = post(
            "/device/code",
            listOf("client_id" to CLIENT_ID, "device_name" to "QiYaa ($deviceName)"),
        )
        val deviceCode = o.str("device_code")
        val userCode = o.str("user_code")
        if (deviceCode.isEmpty() || userCode.isEmpty()) throw AuthException(errorOf(o, "device code request failed"))
        var verify = o.str("verification_url")
        if (!(verify.startsWith("http://") || verify.startsWith("https://"))) verify = "https://ya.ru/device"
        val interval = maxOf(1, (o["interval"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 5)
        val expires = (o["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 300
        return Code(deviceCode, userCode, verify, interval * 1000L, clock() + expires * 1000L)
    }

    /**
     * Step 2: poll until the user confirms. Returns the access token or throws [AuthException].
     * Honors `authorization_pending`, `slow_down` (+2 s) and the code's expiry.
     */
    suspend fun waitForToken(code: Code): String {
        var interval = code.intervalMs
        while (true) {
            delay(interval)
            if (clock() > code.deadlineMs) throw AuthException("Code expired, start again")
            val o = post(
                "/token",
                listOf(
                    "grant_type" to "device_code",
                    "code" to code.deviceCode,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                ),
            )
            val token = o.str("access_token")
            if (token.isNotEmpty()) return token
            when (val error = o.str("error")) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 2000
                else -> throw AuthException(o.str("error_description").ifEmpty { error.ifEmpty { "sign-in failed" } })
            }
        }
    }

    private suspend fun post(path: String, form: List<Pair<String, String>>): JsonObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().also { b -> form.forEach { (k, v) -> b.add(k, v) } }.build()
        val req = Request.Builder().url(baseUrl + path).post(body).build()
        val text = try {
            client.newCall(req).execute().use { it.body?.string().orEmpty() }
        } catch (e: java.io.IOException) {
            throw AuthException(e.message ?: "network error")
        }
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: JsonObject(emptyMap())
    }

    private fun errorOf(o: JsonObject, fallback: String): String =
        o.str("error_description").ifEmpty { o.str("error").ifEmpty { fallback } }

    companion object {
        const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"

        /** Implicit-grant page; the browser lands on music.yandex.ru/#access_token=… */
        const val BROWSER_LOGIN_URL = "https://oauth.yandex.ru/authorize?response_type=token&client_id=$CLIENT_ID"
    }
}
