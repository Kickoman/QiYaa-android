package io.github.kickoman.qiyaa.yandex

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private val account = Account(uid = "42", login = "kick", displayName = "Kick")

    @Test
    fun `start without a token stays LoggedOut and makes no calls`() = runTest {
        val gateway = FakeGateway(token = "")
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        runCurrent()
        assertEquals(SessionState.LoggedOut, session.state.value)
        assertEquals(0, gateway.connectCalls.size)
    }

    @Test
    fun `start offline waits for the network, then goes Online and preloads likes`() = runTest {
        val gateway = FakeGateway { account }
        val network = MutableStateFlow(false)
        val session = Session(gateway, network, backgroundScope)
        session.start()
        advanceTimeBy(10 * MINUTE)
        assertEquals(SessionState.Offline, session.state.value)
        assertEquals(0, gateway.connectCalls.size)

        network.value = true
        runCurrent()
        assertEquals(SessionState.Online(account), session.state.value)
        assertEquals(1, gateway.connectCalls.size)
        assertEquals(1, gateway.preloadCalls)
    }

    @Test
    fun `a failing API is retried with backoff from 2 to 60 seconds`() = runTest {
        var failuresLeft = 8
        val gateway =
            FakeGateway {
                if (failuresLeft-- > 0) throw networkFailure()
                account
            }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        runCurrent()
        assertEquals(SessionState.Offline, session.state.value)

        advanceTimeBy(10 * MINUTE)
        val gaps = gateway.connectCalls.zipWithNext { earlier, later -> (later - earlier) / 1000 }
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L), gaps)
        assertEquals(SessionState.Online(account), session.state.value)
    }

    @Test
    fun `the network coming back retries at once and resets the backoff`() = runTest {
        var failuresLeft = 4
        val gateway =
            FakeGateway {
                if (failuresLeft-- > 0) throw networkFailure()
                account
            }
        val network = MutableStateFlow(true)
        val session = Session(gateway, network, backgroundScope)
        session.start()
        advanceTimeBy(2_000 + 4_000 + 1)
        assertEquals(3, gateway.connectCalls.size)

        network.value = false
        runCurrent()
        network.value = true
        runCurrent()
        assertEquals(4, gateway.connectCalls.size)
        assertEquals(gateway.connectCalls[2] + 1, gateway.connectCalls[3])

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(5, gateway.connectCalls.size)
        assertEquals(SessionState.Online(account), session.state.value)
    }

    @Test
    fun `a rejected token while connecting expires the session without retries`() = runTest {
        val gateway = FakeGateway { throw HttpException(401, "GET", "/account/status", "Unauthorized") }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        advanceTimeBy(10 * MINUTE)
        assertEquals(SessionState.Expired, session.state.value)
        assertEquals(1, gateway.connectCalls.size)
    }

    @Test
    fun `an account without uid expires the session`() = runTest {
        val gateway = FakeGateway { throw AuthException("GET /account/status returned no uid") }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        advanceTimeBy(MINUTE)
        assertEquals(SessionState.Expired, session.state.value)
        assertEquals(1, gateway.connectCalls.size)
    }

    @Test
    fun `a token rejected by any later request expires an Online session`() = runTest {
        val gateway = FakeGateway { account }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        runCurrent()
        assertEquals(SessionState.Online(account), session.state.value)

        gateway.rejections.emit(HttpException(403, "GET", "/users/42/likes/tracks", "Forbidden"))
        runCurrent()
        assertEquals(SessionState.Expired, session.state.value)
    }

    @Test
    fun `token rejections are ignored while logged out`() = runTest {
        val gateway = FakeGateway(token = "") { account }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        runCurrent()
        gateway.rejections.emit(HttpException(401, "GET", "/account/status", "Unauthorized"))
        runCurrent()
        assertEquals(SessionState.LoggedOut, session.state.value)
    }

    @Test
    fun `losing the network while Online goes Offline and re-checks the account when it returns`() = runTest {
        val gateway = FakeGateway { account }
        val network = MutableStateFlow(true)
        val session = Session(gateway, network, backgroundScope)
        session.start()
        runCurrent()

        network.value = false
        runCurrent()
        assertEquals(SessionState.Offline, session.state.value)

        network.value = true
        runCurrent()
        assertEquals(SessionState.Online(account), session.state.value)
        assertEquals(2, gateway.connectCalls.size)
    }

    @Test
    fun `signIn goes Online with the new token`() = runTest {
        val gateway = FakeGateway(token = "") { account }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        runCurrent()
        assertEquals(account, session.signIn("new-token"))
        assertEquals("new-token", gateway.token)
        assertEquals(SessionState.Online(account), session.state.value)
        runCurrent()
        assertEquals(1, gateway.preloadCalls)
    }

    @Test
    fun `a failed signIn clears the token, stays LoggedOut and rethrows`() = runTest {
        val gateway =
            FakeGateway(token = "") { throw HttpException(401, "GET", "/account/status", "bad") }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        runCurrent()
        try {
            session.signIn("wrong-token")
            fail("expected HttpException")
        } catch (expected: HttpException) {
            assertEquals(401, expected.status)
        }
        assertEquals("", gateway.token)
        assertEquals(SessionState.LoggedOut, session.state.value)
    }

    @Test
    fun `signOut forgets the account and stops reconnecting`() = runTest {
        val gateway = FakeGateway { throw networkFailure() }
        val session = Session(gateway, MutableStateFlow(true), backgroundScope)
        session.start()
        runCurrent()
        session.signOut()
        advanceTimeBy(10 * MINUTE)
        assertEquals(SessionState.LoggedOut, session.state.value)
        assertEquals(1, gateway.connectCalls.size)
        assertTrue(gateway.forgotten)
    }

    private fun networkFailure() = NetworkException("GET", "/account/status", IOException("offline"))

    private inner class FakeGateway(
        override var token: String = "saved-token",
        private val connect: suspend () -> Account = { account },
    ) : AccountGateway {
        val rejections = MutableSharedFlow<HttpException>()
        val connectCalls = ArrayList<Long>()
        var preloadCalls = 0
        var forgotten = false

        override val tokenRejections: SharedFlow<HttpException> = rejections

        override suspend fun connectAccount(): Account {
            connectCalls += currentCoroutineContext()[TestCoroutineScheduler]!!.currentTime
            return connect()
        }

        override suspend fun preloadLikes() {
            preloadCalls++
        }

        override fun forget() {
            forgotten = true
            token = ""
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
