package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.support.expectFailure
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

class BrowserLoginTest {
    private val server = MockWebServer()
    private val exchanges = CopyOnWriteArrayList<Map<String, String>>()
    private val browser = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private val logins = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path != PairLogin.TOKEN_PATH) return MockResponse().setResponseCode(404)
                    val form = formOf(request)
                    exchanges += form
                    val verified = BrowserLogin.challengeOf(form["code_verifier"].orEmpty()) == challenge
                    return if (form["code"] == "c1" && verified) {
                        MockResponse().setBody("""{"access_token":"BQ-browser"}""")
                    } else {
                        MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        logins.cancel()
        server.shutdown()
    }

    @Volatile
    private var challenge = ""

    @Test
    fun `SPOT-02 the browser's redirect with its own state is exchanged with the PKCE verifier`() =
        runBlocking {
            val (page, token) = start()
            assertEquals("/authorize", page.encodedPath)
            assertEquals("code", page.queryParameter("response_type"))
            assertEquals(SpotifyIdentity.CLIENT_ID, page.queryParameter("client_id"))
            assertEquals("streaming", page.queryParameter("scope"))
            assertEquals("S256", page.queryParameter("code_challenge_method"))
            val redirect = page.queryParameter("redirect_uri")!!.toHttpUrl()
            assertEquals("127.0.0.1", redirect.host)
            assertEquals("/login", redirect.encodedPath)
            val (status, body) = visit(
                redirect.newBuilder().addQueryParameter("code", "c1").state(page).build(),
            )
            assertEquals(200, status)
            assertTrue(body, body.contains("<p>Logging in &amp; done</p>"))
            assertEquals("BQ-browser", withTimeout(WAIT_MS) { token.await() })
            val exchange = exchanges.single()
            assertEquals("authorization_code", exchange["grant_type"])
            assertEquals(redirect.toString(), exchange["redirect_uri"])
            assertEquals(SpotifyIdentity.CLIENT_ID, exchange["client_id"])
            assertEquals(BrowserLogin.VERIFIER_LENGTH, exchange["code_verifier"]!!.length)
        }

    @Test
    fun `SPOT-02 only GET login counts, anything else gets 404 and the login keeps waiting`() = runBlocking {
        val (page, token) = start()
        val redirect = page.queryParameter("redirect_uri")!!.toHttpUrl()
        val favicon = redirect.newBuilder().encodedPath("/favicon.ico").build()
        assertEquals(404, visit(favicon).first)
        val posted = Request.Builder().url(
            redirect.newBuilder().addQueryParameter("code", "c1").state(page).build(),
        )
            .post(ByteArray(0).toRequestBody()).build()
        assertEquals(404, withContext(Dispatchers.IO) { browser.newCall(posted).execute().use { it.code } })
        assertEquals(false, token.isCompleted)
        assertEquals(
            200,
            visit(redirect.newBuilder().addQueryParameter("code", "c1").state(page).build()).first,
        )
        assertEquals("BQ-browser", withTimeout(WAIT_MS) { token.await() })
    }

    @Test
    fun `SPOT-02 a redirect with another state saves nothing and asks for no token`() = runBlocking {
        val (page, token) = start()
        val redirect = page.queryParameter("redirect_uri")!!.toHttpUrl()
        val (status, body) =
            visit(
                redirect.newBuilder().addQueryParameter(
                    "code",
                    "c1",
                ).addQueryParameter("state", "other").build(),
            )
        assertEquals(200, status)
        assertTrue(body, body.contains("Refused"))
        val failed = expectFailure<AuthorizationException> { token.await() }
        assertEquals(BrowserLogin.STATE_MISMATCH, failed.error)
        assertEquals(emptyList<Map<String, String>>(), exchanges.toList())
    }

    @Test
    fun `SPOT-02 a redirect with an error ends the login with that error`() = runBlocking {
        val (page, token) = start()
        val redirect = page.queryParameter("redirect_uri")!!.toHttpUrl()
        visit(redirect.newBuilder().addQueryParameter("error", "access_denied").state(page).build())
        assertEquals("access_denied", expectFailure<AuthorizationException> { token.await() }.error)
        assertEquals(0, exchanges.size)
    }

    @Test
    fun `a refused exchange is an authorization failure`() = runBlocking {
        val (page, token) = start()
        val redirect = page.queryParameter("redirect_uri")!!.toHttpUrl()
        visit(redirect.newBuilder().addQueryParameter("code", "c2").state(page).build())
        assertEquals("invalid_grant", expectFailure<AuthorizationException> { token.await() }.error)
    }

    @Test
    fun `canceling the login closes its port`() = runBlocking {
        val (page, token) = start()
        val redirect = page.queryParameter("redirect_uri")!!.toHttpUrl()
        val idle = withContext(Dispatchers.IO) { Socket(redirect.host, redirect.port) }
        withTimeout(WAIT_MS) { token.cancelAndJoin() }
        withContext(Dispatchers.IO) {
            try {
                Socket(redirect.host, redirect.port).close()
                fail("the port still accepts connections")
            } catch (ignored: ConnectException) {
                // Refused: the login's port is closed, as it should be.
            }
            assertEquals(-1, readOrEnd(idle))
        }
    }

    private suspend fun start(): Pair<HttpUrl, Deferred<String>> {
        val opened = CompletableDeferred<String>()
        val login =
            BrowserLogin(OkHttpClient(), Endpoints(accounts = server.url("/").toString().removeSuffix("/")))
        val token = logins.async {
            login.login(BrowserLogin.Pages("Logging in & done", "Refused"), opened::complete)
        }
        val page = withTimeout(WAIT_MS) { opened.await() }.toHttpUrl()
        challenge = page.queryParameter("code_challenge")!!
        return page to token
    }

    private suspend fun visit(url: HttpUrl): Pair<Int, String> = withContext(Dispatchers.IO) {
        browser.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }
    }

    private fun HttpUrl.Builder.state(page: HttpUrl): HttpUrl.Builder =
        addQueryParameter("state", page.queryParameter("state"))

    private fun readOrEnd(socket: Socket): Int = try {
        socket.soTimeout = WAIT_MS.toInt()
        socket.getInputStream().read()
    } catch (closed: IOException) {
        -1
    } finally {
        socket.close()
    }

    private fun formOf(request: RecordedRequest): Map<String, String> =
        request.body.clone().readUtf8().split('&').filter(String::isNotEmpty).associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
