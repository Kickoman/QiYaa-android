package io.github.kickoman.qiyaa.yandex

import io.github.kickoman.qiyaa.support.Spec
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

class DeviceAuthTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the device code reply parses to the spec's code`() = runBlocking {
        server.enqueue(Spec.fixture("oauth-device-code", "ok").response())
        val code = DeviceAuth(OkHttpClient(), baseUrl(), deviceName = "Test", clock = {
            0L
        }).requestCode()
        val actual =
            buildJsonObject {
                put("deviceCode", code.deviceCode)
                put("userCode", code.userCode)
                put("verificationUrl", code.verificationUrl)
                put("intervalSeconds", code.intervalMs / 1000)
                put("expiresInSeconds", code.deadlineMs / 1000)
            }
        assertEquals(Spec.expected("oauth-device-code", "ok"), actual)
        val form = server.takeRequest().form()
        assertEquals(DeviceAuth.CLIENT_ID, form["client_id"])
        assertEquals("QiYaa (Test)", form["device_name"])
    }

    @Test
    fun `authorization_pending keeps polling until the token arrives`() = runBlocking {
        assertEquals(
            true,
            Spec.expected(
                "oauth-token",
                "400-authorization-pending",
            )["pending"]?.jsonPrimitive?.content?.toBoolean(),
        )
        var polls = 0
        val seen = ArrayList<Map<String, String>>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request.form()
                    polls++
                    val case = if (polls < 2) "400-authorization-pending" else "ok"
                    return Spec.fixture("oauth-token", case).response()
                }
            }
        val auth = DeviceAuth(OkHttpClient(), baseUrl())
        val token = auth.waitForToken(
            DeviceAuth.Code("DEV", "ABCD1234", "https://ya.ru/device", 1, Long.MAX_VALUE),
        )
        val expected = Spec.expected("oauth-token", "ok").getValue("accessToken").jsonPrimitive.content
        assertEquals(expected, token)
        assertEquals(2, polls)
        assertEquals("device_code", seen.last()["grant_type"])
        assertEquals("DEV", seen.last()["code"])
        assertEquals(DeviceAuth.CLIENT_SECRET, seen.last()["client_secret"])
    }

    @Test
    fun `OAuth errors carry the server's error_description`() = runBlocking {
        for ((endpoint, case) in OAUTH_ERRORS) {
            val message = expectedError(endpoint, case).getValue("message").jsonPrimitive.content
            val failure = oauthFailure(endpoint, case)
            assertTrue("$endpoint/$case: ${failure.message}", failure.message!!.contains(message))
        }
    }

    @Ignore("Known divergence Kickoman/QiYaa-android#33: AuthException does not name the HTTP status")
    @Test
    fun `OAuth errors name the HTTP status`() = runBlocking {
        for ((endpoint, case) in OAUTH_ERRORS) {
            val status = expectedError(endpoint, case).getValue("status").jsonPrimitive.int
            val failure = oauthFailure(endpoint, case)
            assertTrue(
                "$endpoint/$case: ${failure.message}",
                failure.message!!.contains(status.toString()),
            )
        }
    }

    @Test
    fun `waitForToken gives up once the code has expired`() = runBlocking {
        var now = 0L
        val auth = DeviceAuth(OkHttpClient(), baseUrl(), clock = { now })
        val code = DeviceAuth.Code("DEV", "X", "https://ya.ru/device", intervalMs = 1, deadlineMs = 10)
        now = 11
        try {
            auth.waitForToken(code)
            fail("expected AuthException")
        } catch (failed: AuthException) {
            assertTrue(failed.message!!.contains("expired"))
        }
    }

    private suspend fun oauthFailure(endpoint: String, case: String): AuthException {
        server.enqueue(Spec.fixture(endpoint, case).response())
        val auth = DeviceAuth(OkHttpClient(), baseUrl())
        try {
            if (endpoint == "oauth-device-code") {
                auth.requestCode()
            } else {
                auth.waitForToken(DeviceAuth.Code("DEV", "X", "https://ya.ru/device", 1, Long.MAX_VALUE))
            }
        } catch (failed: AuthException) {
            return failed
        }
        throw AssertionError("$endpoint/$case: expected AuthException")
    }

    private fun expectedError(endpoint: String, case: String) =
        Spec.expected(endpoint, case).getValue("error").jsonObject

    private fun baseUrl() = server.url("/").toString().removeSuffix("/")

    private fun RecordedRequest.form(): Map<String, String> = body.readUtf8().split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to
            URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private companion object {
        val OAUTH_ERRORS = listOf(
            "oauth-device-code" to "400-invalid-client",
            "oauth-token" to "400-bad-verification-code",
        )
    }
}
