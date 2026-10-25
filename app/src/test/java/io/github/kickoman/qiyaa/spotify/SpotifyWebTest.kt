package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.Crypto
import io.github.kickoman.qiyaa.spotify.protocol.ProtoWriter
import io.github.kickoman.qiyaa.spotify.protocol.bytesOfHex
import io.github.kickoman.qiyaa.support.SpotifyStack
import io.github.kickoman.qiyaa.support.bytes
import io.github.kickoman.qiyaa.support.expectFailure
import io.github.kickoman.qiyaa.support.fieldOf
import io.github.kickoman.qiyaa.support.varintOf
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyWebTest {
    private val stacks = mutableListOf<SpotifyStack>()

    @After
    fun tearDown() = stacks.forEach(SpotifyStack::close)

    @Test
    fun `the tokens are asked once and go to spclient with QiYaa's User-Agent`() = runBlocking {
        val stack = online()
        stack.route("GET", "/thing") { SpotifyStack.json(EMPTY) }
        repeat(2) { stack.web.spclient(WebRequest("/thing", accept = SpotifyWeb.JSON)) }
        val request = stack.requests("/thing").last()
        assertEquals("Bearer ${SpotifyStack.BEARER}", request.getHeader("Authorization"))
        assertEquals(SpotifyStack.CLIENT_TOKEN, request.getHeader("client-token"))
        assertEquals("Spotify/124200290 Linux/0 (QiYaa 0.8.0)", request.getHeader("User-Agent"))
        assertEquals(SpotifyWeb.JSON, request.getHeader("Accept"))
        assertEquals(1, stack.requests("/v1/clienttoken").size)
        assertEquals(1, stack.requests("/v3/login").size)
    }

    @Test
    fun `client-token asks for the Linux platform and login5 sends the reusable credentials with it`() =
        runBlocking {
            val stack = online()
            stack.route("GET", "/thing") { SpotifyStack.json(EMPTY) }
            stack.web.spclient(WebRequest("/thing"))
            val clientToken = stack.requests("/v1/clienttoken").single()
            assertEquals(SpotifyWeb.PROTOBUF, clientToken.getHeader("Accept"))
            assertEquals(SpotifyWeb.PROTOBUF, clientToken.getHeader("Content-Type"))
            val clientData = fieldOf(clientToken.bytes(), 2)
            assertEquals(SpotifyIdentity.CLIENT_VERSION, fieldOf(clientData, 1).toString(Charsets.UTF_8))
            assertEquals(SpotifyIdentity.CLIENT_ID, fieldOf(clientData, 2).toString(Charsets.UTF_8))
            val linux = fieldOf(fieldOf(fieldOf(clientData, 3), 1), 5)
            assertEquals("Linux", fieldOf(linux, 1).toString(Charsets.UTF_8))
            assertEquals("6.1.25-android16", fieldOf(linux, 2).toString(Charsets.UTF_8))
            assertEquals("16", fieldOf(linux, 3).toString(Charsets.UTF_8))
            assertEquals("aarch64", fieldOf(linux, 4).toString(Charsets.UTF_8))
            val login5 = stack.requests("/v3/login").single()
            assertEquals(SpotifyStack.CLIENT_TOKEN, login5.getHeader("client-token"))
            val clientInfo = fieldOf(login5.bytes(), 1)
            assertEquals(SpotifyIdentity.CLIENT_ID, fieldOf(clientInfo, 1).toString(Charsets.UTF_8))
            assertEquals(SpotifyStack.DEVICE_ID, fieldOf(clientInfo, 2).toString(Charsets.UTF_8))
            val stored = fieldOf(login5.bytes(), 100)
            assertEquals("owner", fieldOf(stored, 1).toString(Charsets.UTF_8))
            assertEquals(listOf<Byte>(9, 9, 9), fieldOf(stored, 2).toList())
        }

    @Test
    fun `spclient adds product, country and salt to the query, after the path's own`() = runBlocking {
        val stack = online()
        stack.route("POST", "/extended-metadata/v0/extended-metadata") { SpotifyStack.protobuf(ByteArray(3)) }
        val body = Bytes.of(byteArrayOf(1, 2))
        val reply =
            stack.web.spclient(
                WebRequest("/extended-metadata/v0/extended-metadata?market=from_token", "POST", body),
            )
        assertEquals(3, reply.size)
        val request = stack.requests("/extended-metadata/v0/extended-metadata").single()
        val url = request.requestUrl!!
        assertEquals(listOf("market", "product", "country", "salt"), url.queryParameterNames.toList())
        assertEquals("from_token", url.queryParameter("market"))
        assertEquals("0", url.queryParameter("product"))
        assertEquals("BY", url.queryParameter("country"))
        assertTrue(url.queryParameter("salt")!!.matches(Regex("\\d+")))
        assertEquals(SpotifyWeb.PROTOBUF, request.getHeader("Content-Type"))
        assertEquals(listOf<Byte>(1, 2), request.bytes().toList())
    }

    @Test
    fun `context-resolve goes without the account query`() = runBlocking {
        val stack = online()
        val path = "/context-resolve/v1/spotify:station:track:6PCoZdjSPbRMehHviTrpB3"
        stack.route("GET", path) { SpotifyStack.json(EMPTY) }
        stack.web.spclient(WebRequest(path, accept = SpotifyWeb.JSON, accountQuery = false))
        val request = stack.requests(path).single()
        assertEquals(null, request.requestUrl!!.query)
        assertEquals(SpotifyWeb.JSON, request.getHeader("Accept"))
    }

    @Test
    fun `Pathfinder takes a JSON query with the same tokens and no account query`() = runBlocking {
        val stack = online()
        stack.route("POST", SpotifyWeb.PATHFINDER_PATH) { SpotifyStack.json(EMPTY) }
        stack.web.pathfinder("""{"operationName":"home"}""")
        val request = stack.requests(SpotifyWeb.PATHFINDER_PATH).single()
        assertEquals("""{"operationName":"home"}""", request.bytes().toString(Charsets.UTF_8))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("Bearer ${SpotifyStack.BEARER}", request.getHeader("Authorization"))
        assertEquals(null, request.requestUrl!!.query)
    }

    @Test
    fun `a 401 forgets the bearer and asks once more with a new one`() = runBlocking {
        val stack = online()
        val bearers = AtomicInteger()
        stack.route("POST", "/v3/login") {
            SpotifyStack.protobuf(SpotifyStack.login5("bearer-${bearers.incrementAndGet()}"))
        }
        val answers = AtomicInteger()
        stack.route("GET", "/thing") {
            val first = answers.incrementAndGet() == 1
            if (first) MockResponse().setResponseCode(401) else SpotifyStack.json(EMPTY)
        }
        stack.web.spclient(WebRequest("/thing"))
        assertEquals(
            listOf("Bearer bearer-1", "Bearer bearer-2"),
            stack.requests("/thing").map { it.getHeader("Authorization") },
        )
        assertEquals(1, stack.requests("/v1/clienttoken").size)
        assertEquals(emptyList<SpotifyProblem>(), stack.problems.value)
    }

    @Test
    fun `a second 401 is an HTTP failure, reported once with its status`() = runBlocking {
        val stack = online()
        stack.route("GET", "/thing") { MockResponse().setResponseCode(401) }
        val failed = expectFailure<SpotifyHttpException> { stack.web.spclient(WebRequest("/thing?id=1")) }
        assertEquals(401, failed.status)
        assertEquals("HTTP 401 on GET /thing", failed.message)
        assertEquals(2, stack.requests("/thing").size)
        assertEquals(listOf<SpotifyProblem>(SpotifyProblem.Http(401)), stack.awaitProblems(1))
    }

    @Test
    fun `a 429 with a Retry-After of up to 5 s is sent once more after it`() = runBlocking {
        val stack = online()
        val answers = AtomicInteger()
        stack.route("GET", "/thing") {
            if (answers.incrementAndGet() == 1) {
                MockResponse().setResponseCode(429).setHeader("Retry-After", "2")
            } else {
                SpotifyStack.json(EMPTY)
            }
        }
        stack.web.spclient(WebRequest("/thing"))
        assertEquals(listOf(2_000L), stack.webPauses.toList())
        assertEquals(2, stack.requests("/thing").size)
    }

    @Test
    fun `a 429 with a longer Retry-After, or a second one, fails at once`() = runBlocking {
        val stack = online()
        stack.route("GET", "/long") { MockResponse().setResponseCode(429).setHeader("Retry-After", "30") }
        assertEquals(
            429,
            expectFailure<SpotifyHttpException> {
                stack.web.spclient(WebRequest("/long"))
            }.status,
        )
        assertEquals(1, stack.requests("/long").size)
        stack.route("GET", "/again") { MockResponse().setResponseCode(429).setHeader("Retry-After", "1") }
        expectFailure<SpotifyHttpException> { stack.web.spclient(WebRequest("/again")) }
        assertEquals(2, stack.requests("/again").size)
        assertEquals(listOf(1_000L), stack.webPauses.toList())
    }

    @Test
    fun `a reply over the size limit is a content failure, with or without its length`() = runBlocking {
        val stack = online(maxBodyBytes = 1_024)
        stack.route("GET", "/fits") { SpotifyStack.protobuf(ByteArray(1_024)) }
        assertEquals(1_024, stack.web.spclient(WebRequest("/fits")).size)
        stack.route("GET", "/sized") { SpotifyStack.protobuf(ByteArray(1_025)) }
        stack.route("GET", "/chunked") { MockResponse().setChunkedBody("x".repeat(5_000), 100) }
        for (path in listOf("/sized", "/chunked")) {
            expectFailure<SpotifyContentException> { stack.web.spclient(WebRequest(path)) }
        }
        assertEquals(listOf(SpotifyProblem.Content, SpotifyProblem.Content), stack.awaitProblems(2))
    }

    @Test
    fun `the tokens are kept until a minute before they expire`() = runBlocking {
        val stack = online()
        stack.route("POST", "/v1/clienttoken") {
            SpotifyStack.protobuf(
                SpotifyStack.clientToken(SpotifyStack.CLIENT_TOKEN, refreshAfterSeconds = 7_200),
            )
        }
        stack.route("GET", "/thing") { SpotifyStack.json(EMPTY) }
        stack.web.spclient(WebRequest("/thing"))
        stack.clock.addAndGet((3_600 - 60) * 1_000L - 1)
        stack.web.spclient(WebRequest("/thing"))
        assertEquals(1, stack.requests("/v3/login").size)
        stack.clock.addAndGet(1)
        stack.web.spclient(WebRequest("/thing"))
        assertEquals(2, stack.requests("/v3/login").size)
        assertEquals(1, stack.requests("/v1/clienttoken").size)
        stack.clock.addAndGet(3_600 * 1_000L)
        stack.web.spclient(WebRequest("/thing"))
        assertEquals(2, stack.requests("/v1/clienttoken").size)
        assertEquals(3, stack.requests("/v3/login").size)
    }

    @Test
    fun `requests that come together wait for one token fetch`() = runBlocking {
        val stack = online()
        stack.route("GET", "/thing") { SpotifyStack.json(EMPTY) }
        List(4) { async(Dispatchers.IO) { stack.web.spclient(WebRequest("/thing")) } }.awaitAll()
        assertEquals(1, stack.requests("/v1/clienttoken").size)
        assertEquals(1, stack.requests("/v3/login").size)
    }

    @Test
    fun `client-token's hashcash is solved and answered with the challenge's state`() = runBlocking {
        val stack = online()
        val prefix = "00112233445566778899aabbccddeeff"
        val answers = AtomicInteger()
        stack.route("POST", "/v1/clienttoken") {
            if (answers.incrementAndGet() == 1) {
                val parameters = ProtoWriter().varint(1, 8).string(2, prefix)
                val challenge = ProtoWriter().varint(1, 1).message(4, parameters)
                SpotifyStack.protobuf(
                    ProtoWriter().message(3, ProtoWriter().string(1, "challenge-state").message(2, challenge))
                        .toByteArray(),
                )
            } else {
                SpotifyStack.protobuf(SpotifyStack.clientToken(SpotifyStack.CLIENT_TOKEN))
            }
        }
        stack.route("GET", "/thing") { SpotifyStack.json(EMPTY) }
        stack.web.spclient(WebRequest("/thing"))
        val answer = stack.requests("/v1/clienttoken")[1].bytes()
        assertEquals(2L, varintOf(answer, 1))
        val answers3 = fieldOf(answer, 3)
        assertEquals("challenge-state", fieldOf(answers3, 1).toString(Charsets.UTF_8))
        val hashcash = fieldOf(fieldOf(answers3, 2), 4)
        val suffix = bytesOfHex(fieldOf(hashcash, 1).toString(Charsets.UTF_8))!!
        assertSolves(bytesOfHex(prefix)!!, suffix, 8)
        assertEquals(SpotifyStack.CLIENT_TOKEN, stack.requests("/thing").single().getHeader("client-token"))
    }

    @Test
    fun `login5's hashcash is solved and sent back with its context and the time it took`() = runBlocking {
        val stack = online()
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val answers = AtomicInteger()
        stack.route("POST", "/v3/login") {
            if (answers.incrementAndGet() == 1) {
                val hashcash = ProtoWriter().bytes(1, prefix).varint(2, 8)
                val challenges = ProtoWriter().message(1, ProtoWriter().message(1, hashcash))
                SpotifyStack.protobuf(
                    ProtoWriter().message(3, challenges).bytes(5, byteArrayOf(0x0c, 0x0d)).toByteArray(),
                )
            } else {
                SpotifyStack.protobuf(SpotifyStack.login5(SpotifyStack.BEARER))
            }
        }
        stack.route("GET", "/thing") { SpotifyStack.json(EMPTY) }
        stack.web.spclient(WebRequest("/thing"))
        val second = stack.requests("/v3/login")[1].bytes()
        assertEquals(listOf<Byte>(0x0c, 0x0d), fieldOf(second, 2).toList())
        val solution = fieldOf(fieldOf(fieldOf(second, 3), 1), 1)
        assertSolves(prefix, fieldOf(solution, 1), 8)
        assertEquals(listOf<Byte>(9, 9, 9), fieldOf(fieldOf(second, 100), 2).toList())
    }

    @Test
    fun `a hashcash not solved within 5 s gives up as a content failure`() = runBlocking {
        val ticks = AtomicLong()
        val stack = online(elapsedNanos = { ticks.addAndGet(1_000_000_000L) })
        stack.route("POST", "/v1/clienttoken") {
            val parameters = ProtoWriter().varint(1, 64).string(2, "00")
            val challenge = ProtoWriter().varint(1, 1).message(4, parameters)
            SpotifyStack.protobuf(
                ProtoWriter().message(3, ProtoWriter().string(1, "s").message(2, challenge)).toByteArray(),
            )
        }
        val failed = expectFailure<SpotifyContentException> { stack.web.spclient(WebRequest("/thing")) }
        assertEquals("POST /v1/clienttoken: hashcash not solved in time", failed.message)
        assertEquals(listOf<SpotifyProblem>(SpotifyProblem.Content), stack.awaitProblems(1))
        assertEquals(1, stack.requests("/v1/clienttoken").size)
    }

    @Test
    fun `a login5 error is a refused login`() = runBlocking {
        val stack = online()
        stack.route("POST", "/v3/login") { SpotifyStack.protobuf(ProtoWriter().varint(2, 1).toByteArray()) }
        val failed = expectFailure<LoginRefusedException> { stack.web.spclient(WebRequest("/thing")) }
        assertEquals(1, failed.code)
        assertEquals(SpotifyWeb.LOGIN5_PATH, failed.where)
        assertEquals(0, stack.requests("/thing").size)
    }

    @Test
    fun `a token endpoint's error status is an HTTP failure`() = runBlocking {
        val stack = online()
        stack.route("POST", "/v1/clienttoken") { MockResponse().setResponseCode(503) }
        val failed = expectFailure<SpotifyHttpException> { stack.web.spclient(WebRequest("/thing")) }
        assertEquals("HTTP 503 on POST /v1/clienttoken", failed.message)
        assertEquals(listOf<SpotifyProblem>(SpotifyProblem.Http(503)), stack.awaitProblems(1))
    }

    @Test
    fun `a network failure keeps its cause and is not reported`() = runBlocking {
        val stack = online()
        stack.route("GET", "/thing") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val failed = expectFailure<SpotifyNetworkException> { stack.web.spclient(WebRequest("/thing")) }
        assertTrue(failed.cause is IOException)
        assertTrue(failed.message!!.startsWith("GET 127.0.0.1/thing failed: "))
        assertEquals(emptyList<SpotifyProblem>(), stack.problems.value)
    }

    @Test
    fun `without a login nothing is asked`() = runBlocking {
        val stack = SpotifyStack().also { stacks += it }
        expectFailure<NotLoggedInException> { stack.web.spclient(WebRequest("/thing")) }
        assertEquals(0, stack.requests("/v1/clienttoken").size)
    }

    @Test
    fun `a token login gives login5 nothing to log in with until APWelcome`() = runBlocking {
        val stack = SpotifyStack().also { stacks += it }
        stack.accessPoint.loginGate = CountDownLatch(1)
        stack.session.login(Credentials.fromAccessToken("BQ-token"))
        expectFailure<NotLoggedInException> { stack.web.spclient(WebRequest("/thing")) }
        assertEquals(0, stack.requests("/v3/login").size)
    }

    private suspend fun online(
        maxBodyBytes: Long = SpotifyWeb.MAX_BODY_BYTES,
        elapsedNanos: () -> Long = System::nanoTime,
    ): SpotifyStack {
        val stack = SpotifyStack(maxBodyBytes = maxBodyBytes, elapsedNanos = elapsedNanos).also {
            stacks += it
        }
        stack.loginStored()
        return stack
    }

    private fun assertSolves(prefix: ByteArray, suffix: ByteArray, zeros: Int) {
        assertEquals(16, suffix.size)
        val hash = Crypto.sha1(prefix, suffix)
        val tail = hash.copyOfRange(12, 20).fold(0L) { value, byte ->
            (value shl 8) or
                (byte.toLong() and 0xff)
        }
        assertTrue("trailing zeros of ${tail.countTrailingZeroBits()}", tail.countTrailingZeroBits() >= zeros)
    }

    private companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}
