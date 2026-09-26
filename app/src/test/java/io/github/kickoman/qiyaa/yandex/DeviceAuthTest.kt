package io.github.kickoman.qiyaa.yandex

import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
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
import org.junit.Test

class DeviceAuthTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `device login polls the token endpoint until the user confirms`() = runBlocking {
        var polls = 0
        val seen = ArrayList<Map<String, String>>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val form = request.form()
                    seen += form
                    return when (request.path) {
                        "/device/code" ->
                            MockResponse().setBody(
                                """{"device_code":"DEV","user_code":"ABCD1234",""" +
                                    """"verification_url":"https://ya.ru/device","interval":0,"expires_in":300}""",
                            )
                        "/token" ->
                            when {
                                form["code"] != "DEV" ->
                                    MockResponse().setResponseCode(
                                        400,
                                    ).setBody("""{"error":"bad_verification_code"}""")
                                ++polls < 2 ->
                                    MockResponse().setResponseCode(
                                        400,
                                    ).setBody("""{"error":"authorization_pending"}""")
                                else -> MockResponse().setBody(
                                    """{"access_token":"NEW_TOKEN","token_type":"bearer"}""",
                                )
                            }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val auth = DeviceAuth(OkHttpClient(), baseUrl(), deviceName = "Test")
        val code = auth.requestCode()
        assertEquals("ABCD1234", code.userCode)
        assertEquals("https://ya.ru/device", code.verificationUrl)
        assertEquals(1000L, code.intervalMs)
        assertEquals(DeviceAuth.CLIENT_ID, seen[0]["client_id"])
        assertEquals("QiYaa (Test)", seen[0]["device_name"])
        val token = auth.waitForToken(code)
        assertEquals("NEW_TOKEN", token)
        assertEquals(2, polls)
        val tokenRequest = seen.last()
        assertEquals("device_code", tokenRequest["grant_type"])
        assertEquals(DeviceAuth.CLIENT_SECRET, tokenRequest["client_secret"])
    }

    @Test
    fun `a failed code request reports the server's error_description`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"invalid_client","error_description":"Client not found"}"""),
        )
        val auth = DeviceAuth(OkHttpClient(), baseUrl())
        try {
            auth.requestCode()
            fail("expected AuthException")
        } catch (failed: AuthException) {
            assertEquals("Client not found", failed.message)
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

    private fun baseUrl() = server.url("/").toString().removeSuffix("/")

    private fun RecordedRequest.form(): Map<String, String> = body.readUtf8().split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to
            URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
}
