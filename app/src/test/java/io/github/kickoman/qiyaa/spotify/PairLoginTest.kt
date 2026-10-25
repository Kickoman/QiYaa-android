package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.support.expectFailure
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PairLoginTest {
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val forms = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val pauses = CopyOnWriteArrayList<Long>()
    private var now = 0L

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    forms += path to formOf(request)
                    return routes[path]?.invoke(request) ?: MockResponse().setResponseCode(404)
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `SPOT-01 the code and its page come from device authorize, and polling ends with the token`() =
        runBlocking {
            routes[PairLogin.AUTHORIZE_PATH] = { json(CODE_REPLY) }
            val polls = AtomicInteger()
            routes[PairLogin.TOKEN_PATH] = {
                if (polls.incrementAndGet() == 1) {
                    json(mapOf("error" to "authorization_pending"), 400)
                } else {
                    json(mapOf("access_token" to "BQ-token"))
                }
            }
            val login = login()
            val code = login.requestCode()
            assertEquals("ABCDEF", code.userCode)
            assertEquals("https://spotify.com/pair?code=ABCDEF", code.verificationUrl)
            assertEquals(1_000L, code.intervalMs)
            assertEquals(600_000L, code.deadlineMs)
            assertEquals("BQ-token", login.waitForToken(code))
            assertEquals(listOf(1_000L, 1_000L), pauses.toList())
            val (authorizePath, authorize) = forms.first()
            assertEquals(PairLogin.AUTHORIZE_PATH, authorizePath)
            assertEquals(mapOf("client_id" to SpotifyIdentity.CLIENT_ID, "scope" to "streaming"), authorize)
            val (_, poll) = forms.last()
            assertEquals(
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to "device",
                    "client_id" to SpotifyIdentity.CLIENT_ID,
                ),
                poll,
            )
        }

    @Test
    fun `SPOT-01 slow_down polls 5 s later from then on`() = runBlocking {
        routes[PairLogin.AUTHORIZE_PATH] = { json(CODE_REPLY) }
        val polls = AtomicInteger()
        routes[PairLogin.TOKEN_PATH] = {
            when (polls.incrementAndGet()) {
                1 -> json(mapOf("error" to "slow_down"), 400)
                2 -> json(mapOf("error" to "authorization_pending"), 400)
                else -> json(mapOf("access_token" to "BQ-token"))
            }
        }
        val login = login()
        assertEquals("BQ-token", login.waitForToken(login.requestCode()))
        assertEquals(listOf(1_000L, 6_000L, 6_000L), pauses.toList())
    }

    @Test
    fun `SPOT-01 access_denied and expired_token end the login`() = runBlocking {
        routes[PairLogin.AUTHORIZE_PATH] = { json(CODE_REPLY) }
        for (error in listOf("access_denied", "expired_token")) {
            routes[PairLogin.TOKEN_PATH] = { json(mapOf("error" to error), 400) }
            val login = login()
            val failed = expectFailure<AuthorizationException> { login.waitForToken(login.requestCode()) }
            assertEquals(error, failed.error)
            assertEquals("Spotify did not authorize the login on POST /api/token: $error", failed.message)
        }
    }

    @Test
    fun `SPOT-01 a code past its lifetime ends the login without asking again`() = runBlocking {
        routes[PairLogin.AUTHORIZE_PATH] = { json(CODE_REPLY) }
        val login = login()
        val code = login.requestCode()
        now = code.deadlineMs + 1
        val failed = expectFailure<AuthorizationException> { login.waitForToken(code) }
        assertEquals(PairLogin.EXPIRED_TOKEN, failed.error)
        assertEquals(listOf(PairLogin.AUTHORIZE_PATH), forms.map { it.first })
    }

    @Test
    fun `a device code reply without the codes is refused`() = runBlocking {
        routes[PairLogin.AUTHORIZE_PATH] = { json(mapOf("device_code" to "device")) }
        expectFailure<SpotifyContentException> { login().requestCode() }
        routes[PairLogin.AUTHORIZE_PATH] = { json(mapOf("error" to "invalid_client"), 400) }
        assertEquals("invalid_client", expectFailure<AuthorizationException> { login().requestCode() }.error)
        routes[PairLogin.AUTHORIZE_PATH] = { MockResponse().setResponseCode(503) }
        assertEquals(503, expectFailure<SpotifyHttpException> { login().requestCode() }.status)
    }

    @Test
    fun `a page that is not a web address falls back to spotify com pair`() = runBlocking {
        routes[PairLogin.AUTHORIZE_PATH] = {
            json(
                mapOf("device_code" to "device", "user_code" to "ABCDEF", "verification_uri_complete" to "x"),
            )
        }
        val code = login().requestCode()
        assertEquals(PairLogin.DEFAULT_VERIFICATION_URL, code.verificationUrl)
        assertEquals(5_000L, code.intervalMs)
        assertEquals(false, code.toString().contains("ABCDEF"))
    }

    private fun login(): PairLogin = PairLogin(
        OkHttpClient(),
        Endpoints(accounts = server.url("/").toString().removeSuffix("/")),
        clock = { now },
        pause = { pauses += it },
    )

    private fun json(fields: Map<String, Any>, status: Int = 200): MockResponse {
        val body =
            JsonObject(
                fields.mapValues { (_, value) ->
                    if (value is Int) JsonPrimitive(value) else JsonPrimitive("$value")
                },
            )
        return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody(body.toString())
    }

    private fun formOf(request: RecordedRequest): Map<String, String> =
        request.body.clone().readUtf8().split('&').filter(String::isNotEmpty).associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    private companion object {
        val CODE_REPLY: Map<String, Any> = mapOf(
            "device_code" to "device",
            "user_code" to "ABCDEF",
            "verification_uri" to "https://spotify.com/pair",
            "verification_uri_complete" to "https://spotify.com/pair?code=ABCDEF",
            "expires_in" to 600,
            "interval" to 1,
        )
    }
}
