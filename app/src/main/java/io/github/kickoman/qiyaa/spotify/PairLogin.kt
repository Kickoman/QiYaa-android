package io.github.kickoman.qiyaa.spotify

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

class PairLogin(
    private val client: OkHttpClient,
    private val endpoints: Endpoints = Endpoints(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    data class Code(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalMs: Long,
        val deadlineMs: Long,
    ) {
        override fun toString(): String =
            "Code(deviceCode=<${deviceCode.length} chars>, userCode=<${userCode.length} chars>, " +
                "intervalMs=$intervalMs, deadlineMs=$deadlineMs)"
    }

    suspend fun requestCode(): Code {
        val reply =
            client.postForm(
                endpoints.accounts,
                AUTHORIZE_PATH,
                listOf(CLIENT_ID to SpotifyIdentity.CLIENT_ID, SCOPE to STREAMING),
            )
        val deviceCode = reply.text(DEVICE_CODE)
        val userCode = reply.text(USER_CODE)
        if (deviceCode.isEmpty() || userCode.isEmpty()) throw reply.failure()
        val page =
            reply.text(VERIFICATION_URI_COMPLETE).takeIf(::isWebPage)
                ?: reply.text(VERIFICATION_URI).takeIf(::isWebPage)
                ?: DEFAULT_VERIFICATION_URL
        val intervalSeconds = maxOf(1, reply.int(INTERVAL) ?: DEFAULT_INTERVAL_SECONDS)
        val lifetimeSeconds = reply.int(EXPIRES_IN) ?: DEFAULT_EXPIRES_IN_SECONDS
        return Code(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = page,
            intervalMs = intervalSeconds * MS_PER_SECOND,
            deadlineMs = clock() + lifetimeSeconds * MS_PER_SECOND,
        )
    }

    suspend fun waitForToken(code: Code): String {
        var intervalMs = code.intervalMs
        while (true) {
            pause(intervalMs)
            if (clock() > code.deadlineMs) throw AuthorizationException(EXPIRED_TOKEN, "POST $TOKEN_PATH")
            val reply =
                client.postForm(
                    endpoints.accounts,
                    TOKEN_PATH,
                    listOf(
                        GRANT_TYPE to DEVICE_CODE_GRANT,
                        DEVICE_CODE to code.deviceCode,
                        CLIENT_ID to SpotifyIdentity.CLIENT_ID,
                    ),
                )
            val token = reply.text(ACCESS_TOKEN)
            if (token.isNotEmpty()) return token
            when (reply.text(FormReply.ERROR)) {
                AUTHORIZATION_PENDING -> Unit
                SLOW_DOWN -> intervalMs += SLOW_DOWN_STEP_MS
                else -> throw reply.failure()
            }
        }
    }

    private fun isWebPage(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    companion object {
        const val AUTHORIZE_PATH = "/oauth2/device/authorize"
        const val TOKEN_PATH = "/api/token"
        const val CLIENT_ID = "client_id"
        const val SCOPE = "scope"
        const val STREAMING = "streaming"
        const val GRANT_TYPE = "grant_type"
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val DEVICE_CODE = "device_code"
        const val USER_CODE = "user_code"
        const val VERIFICATION_URI = "verification_uri"
        const val VERIFICATION_URI_COMPLETE = "verification_uri_complete"
        const val INTERVAL = "interval"
        const val EXPIRES_IN = "expires_in"
        const val ACCESS_TOKEN = "access_token"
        const val AUTHORIZATION_PENDING = "authorization_pending"
        const val SLOW_DOWN = "slow_down"
        const val EXPIRED_TOKEN = "expired_token"
        const val DEFAULT_VERIFICATION_URL = "https://spotify.com/pair"
        const val DEFAULT_INTERVAL_SECONDS = 5
        const val DEFAULT_EXPIRES_IN_SECONDS = 600
        const val SLOW_DOWN_STEP_MS = 5_000L
        private const val MS_PER_SECOND = 1_000L
    }
}
