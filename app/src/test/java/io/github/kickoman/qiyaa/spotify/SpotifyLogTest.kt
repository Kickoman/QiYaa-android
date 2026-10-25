package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.support.SpotifyStack
import io.github.kickoman.qiyaa.support.expectFailure
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLogTest {
    private val stack = SpotifyStack(SessionTiming(keyTimeoutMs = 100, keyRetryMs = 10), deviceId = DEVICE_ID)

    @After
    fun tearDown() = stack.close()

    @Test
    fun `logs, failures and states carry no tokens, credentials, usernames or device ids`() = runBlocking {
        val messages = mutableListOf<String>()
        stack.accessPoint.welcomeBlob = BLOB
        stack.route("POST", "/v1/clienttoken") {
            SpotifyStack.protobuf(SpotifyStack.clientToken(CLIENT_TOKEN))
        }
        stack.route("POST", "/v3/login") {
            SpotifyStack.protobuf(SpotifyStack.login5(BEARER, username = USERNAME))
        }
        stack.session.login(Credentials(USERNAME, AuthenticationType.STORED_CREDENTIALS, Bytes.of(BLOB)))
        messages += stack.awaitOnline().toString()
        messages += stack.identity.toString()
        messages += stack.session.credentials.toString()

        val rootlist = "/playlist/v2/user/$USERNAME/rootlist"
        val answers = AtomicInteger()
        stack.route("GET", rootlist) {
            val first = answers.incrementAndGet() == 1
            MockResponse().setResponseCode(if (first) 401 else 503)
        }
        val request = WebRequest("$rootlist?decorate=revision", label = "/playlist/v2/user/<user>/rootlist")
        messages += expectFailure<SpotifyHttpException> { stack.web.spclient(request) }.message.orEmpty()

        stack.accessPoint.keyRefusal = { 2 }
        messages += expectFailure<KeyFailedException> { stack.session.audioKey(GID, FILE) }.message.orEmpty()
        stack.accessPoint.answerKeys = false
        messages += expectFailure<KeyFailedException> { stack.session.audioKey(GID, FILE) }.message.orEmpty()

        stack.accessPoint.dropConnections()
        stack.accessPoint.awaitConnections(2)
        stack.awaitOnline()
        stack.session.logout()
        messages +=
            expectFailure<NotLoggedInException> { stack.session.audioKey(GID, FILE) }.message.orEmpty()

        val everything = messages + stack.logged
        assertEquals(true, stack.logged.isNotEmpty())
        val secrets = listOf(USERNAME, DEVICE_ID, BEARER, CLIENT_TOKEN, Bytes.of(BLOB).hex(), String(BLOB))
        for (secret in secrets) {
            assertEquals("lines with $secret", emptyList<String>(), everything.filter { it.contains(secret) })
        }
    }

    private companion object {
        const val USERNAME = "secret-user-7f3a"
        const val DEVICE_ID = "f00dfeedf00dfeedf00dfeedf00dfeedf00dfeed"
        const val BEARER = "secret-bearer-91c2"
        const val CLIENT_TOKEN = "secret-client-token-5e1d"
        val BLOB = "secret-blob-8a4b".toByteArray()
        val GID: Bytes = Bytes.of(ByteArray(16))
        val FILE: Bytes = Bytes.of(ByteArray(20))
    }
}
