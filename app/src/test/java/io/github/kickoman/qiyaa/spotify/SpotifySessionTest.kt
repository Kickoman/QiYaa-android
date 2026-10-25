package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.ApCpuFamily
import io.github.kickoman.qiyaa.spotify.protocol.ApOs
import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.LoginError
import io.github.kickoman.qiyaa.support.FakeAccessPoint
import io.github.kickoman.qiyaa.support.FakeAccessPointServer
import io.github.kickoman.qiyaa.support.SpotifyStack
import io.github.kickoman.qiyaa.support.expectFailure
import io.github.kickoman.qiyaa.support.fieldOf
import io.github.kickoman.qiyaa.support.varintOf
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifySessionTest {
    private val stacks = mutableListOf<SpotifyStack>()
    private val servers = mutableListOf<FakeAccessPointServer>()

    @After
    fun tearDown() {
        stacks.forEach(SpotifyStack::close)
        servers.forEach(FakeAccessPointServer::close)
    }

    @Test
    fun `SPOT-03 a login keeps the reusable credentials of APWelcome, the country and the product`() =
        runBlocking {
            val stack = stack()
            stack.accessPoint.welcomeBlob = byteArrayOf(7, 7, 7, 7)
            val online = stack.loginStored()
            assertEquals(SpotifyState.Online("owner", "BY", "premium"), online)
            assertEquals(
                Credentials(
                    "owner",
                    AuthenticationType.STORED_CREDENTIALS,
                    Bytes.of(byteArrayOf(7, 7, 7, 7)),
                ),
                stack.session.credentials,
            )
            assertEquals("BY", stack.session.country)
            val credentials = fieldOf(stack.accessPoint.logins().single().payload, 10)
            assertEquals("owner", fieldOf(credentials, 10).toString(Charsets.UTF_8))
            assertEquals(1L, varintOf(credentials, 20))
            assertEquals(listOf<Byte>(1, 2, 3), fieldOf(credentials, 30).toList())
        }

    @Test
    fun `the login tells the access point Linux on ARM with the app's version and the device id`() =
        runBlocking {
            val stack = stack()
            stack.loginStored()
            val login = stack.accessPoint.logins().single().payload
            val system = fieldOf(login, 50)
            assertEquals(ApCpuFamily.ARM.code.toLong(), varintOf(system, 10))
            assertEquals(ApOs.LINUX.code.toLong(), varintOf(system, 60))
            assertEquals("QiYaa 0.8.0", fieldOf(system, 90).toString(Charsets.UTF_8))
            assertEquals(SpotifyStack.DEVICE_ID, fieldOf(system, 100).toString(Charsets.UTF_8))
            assertEquals("QiYaa 0.8.0", fieldOf(login, 70).toString(Charsets.UTF_8))
        }

    @Test
    fun `SPOT-03 a token logs in once and the reusable credentials take its place`() = runBlocking {
        val stack = stack()
        stack.session.login(Credentials.fromAccessToken("BQ-token"))
        stack.awaitOnline()
        val credentials = fieldOf(stack.accessPoint.logins().single().payload, 10)
        assertEquals(0, fieldOf(credentials, 10).size)
        assertEquals(AuthenticationType.SPOTIFY_TOKEN.code.toLong(), varintOf(credentials, 20))
        assertEquals("BQ-token", fieldOf(credentials, 30).toString(Charsets.UTF_8))
        val reusable = stack.session.credentials!!
        assertEquals(AuthenticationType.STORED_CREDENTIALS, reusable.type)
        assertEquals(Bytes.of(byteArrayOf(9, 9, 9)), reusable.data)
    }

    @Test
    fun `SPOT-04 BadCredentials ends the session as Expired without connecting again`() = runBlocking {
        val stack = stack()
        stack.accessPoint.loginRefusal = LoginError.BAD_CREDENTIALS.code
        stack.session.login(SpotifyStack.STORED)
        stack.awaitState(SpotifyState.Expired)
        assertNull(stack.session.credentials)
        assertEquals(
            listOf<SpotifyProblem>(SpotifyProblem.LoginRefused(LoginError.BAD_CREDENTIALS.code)),
            stack.awaitProblems(1),
        )
        expectFailure<NotLoggedInException> { stack.session.audioKey(GID, FILE) }
        delay(QUIET_MS)
        assertEquals(1, stack.accessPoint.connectionCount)
        assertEquals(emptyList<Long>(), stack.pauses.value)
    }

    @Test
    fun `SPOT-05 PremiumAccountRequired ends the session and audio keys are refused logins`() = runBlocking {
        val stack = stack()
        stack.accessPoint.loginRefusal = LoginError.PREMIUM_ACCOUNT_REQUIRED.code
        stack.session.login(SpotifyStack.STORED)
        stack.awaitState(SpotifyState.PremiumRequired)
        val refused = expectFailure<LoginRefusedException> { stack.session.audioKey(GID, FILE) }
        assertEquals(LoginError.PREMIUM_ACCOUNT_REQUIRED.code, refused.code)
        delay(QUIET_MS)
        assertEquals(1, stack.accessPoint.connectionCount)
    }

    @Test
    fun `another refusal is Refused with its code`() = runBlocking {
        val stack = stack()
        stack.accessPoint.loginRefusal = LoginError.TRAVEL_RESTRICTION.code
        stack.session.login(SpotifyStack.STORED)
        stack.awaitState(SpotifyState.Refused(LoginError.TRAVEL_RESTRICTION.code))
        Unit
    }

    @Test
    fun `TryAnotherAP moves on to the next access point at once`() = runBlocking {
        val stack = stack()
        val busy = server().apply { loginRefusal = LoginError.TRY_ANOTHER_AP.code }
        stack.accessPoints = listOf(busy.address, stack.accessPoint.address)
        stack.loginStored()
        assertEquals(1, busy.connectionCount)
        assertEquals(1, stack.accessPoint.connectionCount)
        assertEquals(emptyList<Long>(), stack.pauses.value)
    }

    @Test
    fun `an access point that refuses the connection hands over to the next one`() = runBlocking {
        val stack = stack()
        stack.accessPoints = listOf(stack.refusingAddress(), stack.accessPoint.address)
        stack.loginStored()
        assertEquals(emptyList<Long>(), stack.pauses.value)
    }

    @Test
    fun `an access point silent after the hello hands over to the next one after the handshake timeout`() =
        runBlocking {
            val stack = stack(SessionTiming(handshakeTimeoutMs = 200))
            val silent = server().apply { loginGate = CountDownLatch(1) }
            stack.accessPoints = listOf(silent.address, stack.accessPoint.address)
            stack.loginStored()
            assertEquals(1, silent.logins().size)
            silent.loginGate.countDown()
            silent.awaitClosed(1)
        }

    @Test
    fun `SPOT-18 a dropped connection logs in again with the reusable credentials after 2 s`() = runBlocking {
        val stack = stack()
        stack.accessPoint.welcomeBlob = byteArrayOf(4, 4)
        stack.loginStored()
        stack.accessPoint.dropConnections()
        stack.accessPoint.awaitConnections(2)
        stack.awaitOnline()
        assertEquals(listOf(2_000L), stack.pauses.value)
        val second = fieldOf(stack.accessPoint.logins()[1].payload, 10)
        assertEquals(AuthenticationType.STORED_CREDENTIALS.code.toLong(), varintOf(second, 20))
        assertEquals(listOf<Byte>(4, 4), fieldOf(second, 30).toList())
    }

    @Test
    fun `SPOT-18 failed rounds wait 2, 4, 8, 16, 32 s, then 60 s, and ask apresolve again each time`() =
        runBlocking {
            val stack = stack()
            stack.accessPoints = listOf(stack.refusingAddress())
            stack.maxPauses = 8
            stack.session.login(SpotifyStack.STORED)
            val pauses = stack.awaitPauses(8)
            assertEquals(listOf(2_000L, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000), pauses)
            assertEquals(8, stack.requests("/").size)
            assertEquals(SpotifyState.Offline, stack.session.state.value)
        }

    @Test
    fun `SPOT-18 a login after a drop starts the delays from 2 s again`() = runBlocking {
        val stack = stack()
        stack.loginStored()
        stack.accessPoint.dropConnections()
        stack.accessPoint.awaitConnections(2)
        stack.awaitOnline()
        stack.accessPoint.dropConnections()
        stack.accessPoint.awaitConnections(3)
        stack.awaitOnline()
        assertEquals(listOf(2_000L, 2_000L), stack.pauses.value)
        assertEquals(1, stack.requests("/").size)
    }

    @Test
    fun `without the network the session waits for it and connects as soon as it returns`() = runBlocking {
        val stack = stack()
        stack.network.value = false
        stack.session.login(SpotifyStack.STORED)
        stack.awaitState(SpotifyState.Offline)
        delay(QUIET_MS)
        assertEquals(0, stack.accessPoint.connectionCount)
        stack.network.value = true
        stack.awaitOnline()
        assertEquals(emptyList<Long>(), stack.pauses.value)
    }

    @Test
    fun `losing the network closes the connection, and its return connects again at once`() = runBlocking {
        val stack = stack()
        stack.loginStored()
        stack.network.value = false
        stack.accessPoint.awaitClosed(1)
        stack.awaitState(SpotifyState.Offline)
        stack.network.value = true
        stack.accessPoint.awaitConnections(2)
        stack.awaitOnline()
        Unit
    }

    @Test
    fun `SPOT-18 in the background a dropped connection waits until the app is in use again`() = runBlocking {
        val stack = stack(inUse = false)
        stack.loginStored()
        stack.accessPoint.dropConnections()
        stack.awaitState(SpotifyState.Offline)
        delay(QUIET_MS)
        assertEquals(1, stack.accessPoint.connectionCount)
        assertEquals(emptyList<Long>(), stack.pauses.value)
        stack.inUse.value = true
        stack.accessPoint.awaitConnections(2)
        stack.awaitOnline()
        Unit
    }

    @Test
    fun `SPOT-18 in the background the next audio key connects a dropped connection again`() = runBlocking {
        val stack = stack(inUse = false)
        stack.loginStored()
        stack.accessPoint.dropConnections()
        stack.awaitState(SpotifyState.Offline)
        val key = withTimeout(WAIT_MS) { stack.session.audioKey(GID, FILE) }
        assertEquals(Bytes.of(ByteArray(16) { 0x5a }), key)
        assertEquals(2, stack.accessPoint.connectionCount)
    }

    @Test
    fun `an audio key comes back for the file and the track it was asked for`() = runBlocking {
        val stack = stack()
        stack.loginStored()
        val key = withTimeout(WAIT_MS) { stack.session.audioKey(GID, FILE) }
        assertEquals(Bytes.of(ByteArray(16) { 0x5a }), key)
        val request = stack.accessPoint.keyPackets().single().payload
        assertEquals(FILE, Bytes.of(request.copyOfRange(0, 20)))
        assertEquals(GID, Bytes.of(request.copyOfRange(20, 36)))
        assertEquals(listOf<Byte>(0, 0), request.copyOfRange(40, 42).toList())
    }

    @Test
    fun `SPOT-17 AesKeyError code 1 refuses the key for the account without asking again`() = runBlocking {
        val stack = stack()
        stack.loginStored()
        stack.accessPoint.keyRefusal = { 1 }
        expectFailure<KeyRefusedException> { stack.session.audioKey(GID, FILE) }
        assertEquals(1, stack.accessPoint.keyPackets().size)
        assertEquals(listOf<SpotifyProblem>(SpotifyProblem.KeyRefused), stack.awaitProblems(1))
    }

    @Test
    fun `SPOT-16 AesKeyError code 2 is asked once more after the retry delay`() = runBlocking {
        val stack = stack()
        stack.loginStored()
        stack.accessPoint.keyRefusal = { attempt -> if (attempt == 1) 2 else null }
        val key = withTimeout(WAIT_MS) { stack.session.audioKey(GID, FILE) }
        assertEquals(Bytes.of(ByteArray(16) { 0x5a }), key)
        assertEquals(2, stack.accessPoint.keyPackets().size)
        assertEquals(emptyList<SpotifyProblem>(), stack.problems.value)
    }

    @Test
    fun `SPOT-16 a second AesKeyError code 2 is a failed key`() = runBlocking {
        val stack = stack()
        stack.loginStored()
        stack.accessPoint.keyRefusal = { 2 }
        val failed = expectFailure<KeyFailedException> { stack.session.audioKey(GID, FILE) }
        assertEquals("No audio key after two attempts: AesKeyError code 2", failed.message)
        assertEquals(2, stack.accessPoint.keyPackets().size)
        assertEquals(listOf<SpotifyProblem>(SpotifyProblem.KeyFailed), stack.awaitProblems(1))
    }

    @Test
    fun `SPOT-16 no answer within the key timeout is asked once more, then fails`() = runBlocking {
        val stack = stack(SessionTiming(keyTimeoutMs = 100, keyRetryMs = 10))
        stack.loginStored()
        stack.accessPoint.answerKeys = false
        val failed = expectFailure<KeyFailedException> { stack.session.audioKey(GID, FILE) }
        assertEquals("No audio key after two attempts: no answer in 100 ms", failed.message)
        assertEquals(2, stack.accessPoint.keyPackets().size)
    }

    @Test
    fun `a key asked while the session connects waits for the login and goes out after it`() = runBlocking {
        val stack = stack()
        stack.accessPoint.loginGate = CountDownLatch(1)
        stack.session.login(SpotifyStack.STORED)
        stack.accessPoint.awaitPackets(FakeAccessPoint.LOGIN)
        val key = async(Dispatchers.IO) { stack.session.audioKey(GID, FILE) }
        withTimeout(WAIT_MS) { stack.session.keysWaiting.first { it == 1 } }
        assertEquals(SpotifyState.Connecting, stack.session.state.value)
        stack.accessPoint.loginGate.countDown()
        assertEquals(Bytes.of(ByteArray(16) { 0x5a }), withTimeout(WAIT_MS) { key.await() })
        assertEquals(
            listOf(FakeAccessPoint.LOGIN, FakeAccessPoint.REQUEST_KEY),
            stack.accessPoint.packets.map { it.command },
        )
    }

    @Test
    fun `a key waits for the login at most the login wait, then fails as a network failure`() = runBlocking {
        val stack = stack(SessionTiming(loginWaitMs = 100))
        stack.accessPoint.loginGate = CountDownLatch(1)
        stack.session.login(SpotifyStack.STORED)
        val failed = expectFailure<SpotifyNetworkException> { stack.session.audioKey(GID, FILE) }
        assertTrue(failed.cause is SocketTimeoutException)
        assertEquals(SpotifyState.Connecting, stack.session.state.value)
        assertEquals(0, stack.session.keysWaiting.value)
    }

    @Test
    fun `a key in flight when the connection drops fails at once as a network failure`() = runBlocking {
        val stack = stack(SessionTiming(keyTimeoutMs = 60_000))
        stack.loginStored()
        stack.accessPoint.answerKeys = false
        val key = async(Dispatchers.IO) { runCatching { stack.session.audioKey(GID, FILE) } }
        stack.accessPoint.awaitPackets(FakeAccessPoint.REQUEST_KEY)
        stack.accessPoint.dropConnections()
        val failed = withTimeout(WAIT_MS) { key.await() }.exceptionOrNull()
        assertTrue("$failed", failed is SpotifyNetworkException && failed.cause is IOException)
        assertEquals(1, stack.accessPoint.keyPackets().size)
    }

    @Test
    fun `SPOT-06 logout closes the connection and keys fail as not logged in`() = runBlocking {
        val stack = stack(SessionTiming(keyTimeoutMs = 60_000))
        stack.loginStored()
        stack.accessPoint.answerKeys = false
        val key = async(Dispatchers.IO) { runCatching { stack.session.audioKey(GID, FILE) } }
        stack.accessPoint.awaitPackets(FakeAccessPoint.REQUEST_KEY)
        stack.session.logout()
        assertEquals(SpotifyState.LoggedOut, stack.session.state.value)
        assertNull(stack.session.credentials)
        assertTrue(withTimeout(WAIT_MS) { key.await() }.exceptionOrNull() is NotLoggedInException)
        stack.accessPoint.awaitClosed(1)
        expectFailure<NotLoggedInException> { stack.session.audioKey(GID, FILE) }
        delay(QUIET_MS)
        assertEquals(SpotifyState.LoggedOut, stack.session.state.value)
        assertEquals(1, stack.accessPoint.connectionCount)
    }

    @Test
    fun `a key request cancelled right after it registered leaves no key waiting in the background`() =
        runBlocking {
            val stack = stack(inUse = false)
            val accessPointEntered = CountDownLatch(1)
            val accessPointHeld = CountDownLatch(1)
            stack.onLog = { line ->
                if (line == "logged in at the access point" && accessPointEntered.count > 0) {
                    accessPointEntered.countDown()
                    accessPointHeld.await(WAIT_MS, TimeUnit.MILLISECONDS)
                }
            }
            stack.session.login(SpotifyStack.STORED)
            assertTrue(accessPointEntered.await(WAIT_MS, TimeUnit.MILLISECONDS))
            val caller = Executors.newSingleThreadExecutor()
            try {
                val dispatcher = caller.asCoroutineDispatcher()
                val request = stack.scope.launch(dispatcher) { stack.session.audioKey(GID, FILE) }
                val callerBlocked = CountDownLatch(1)
                val callerHeld = CountDownLatch(1)
                caller.execute {
                    callerBlocked.countDown()
                    callerHeld.await(WAIT_MS, TimeUnit.MILLISECONDS)
                }
                assertTrue(callerBlocked.await(WAIT_MS, TimeUnit.MILLISECONDS))
                accessPointHeld.countDown()
                withTimeout(WAIT_MS) { stack.session.keysWaiting.first { it == 1 } }
                request.cancel()
                callerHeld.countDown()
                withTimeout(WAIT_MS) { request.join() }
                withTimeout(WAIT_MS) { stack.session.keysWaiting.first { it == 0 } }
            } finally {
                caller.shutdown()
            }
            stack.awaitOnline()
            stack.accessPoint.dropConnections()
            stack.awaitState(SpotifyState.Offline)
            delay(QUIET_MS)
            assertEquals(0, stack.session.keysWaiting.value)
            assertEquals(1, stack.accessPoint.connectionCount)
        }

    @Test
    fun `SPOT-04 SPOT-05 a refused login fails the keys that wait for it at once`() = runBlocking {
        val refusals =
            listOf(
                LoginError.BAD_CREDENTIALS.code to NotLoggedInException::class.java,
                LoginError.PREMIUM_ACCOUNT_REQUIRED.code to LoginRefusedException::class.java,
            )
        for ((code, expected) in refusals) {
            val stack = stack(SessionTiming(loginWaitMs = 60_000, keyRetryMs = 10))
            stack.accessPoint.loginGate = CountDownLatch(1)
            stack.session.login(SpotifyStack.STORED)
            stack.accessPoint.awaitPackets(FakeAccessPoint.LOGIN)
            val key = async(Dispatchers.IO) { runCatching { stack.session.audioKey(GID, FILE) } }
            withTimeout(WAIT_MS) { stack.session.keysWaiting.first { it == 1 } }
            stack.accessPoint.loginRefusal = code
            stack.accessPoint.loginGate.countDown()
            val failed = withTimeout(WAIT_MS) { key.await() }.exceptionOrNull()
            assertTrue("code $code: $failed", expected.isInstance(failed))
            assertEquals(0, stack.session.keysWaiting.value)
        }
    }

    @Test
    fun `a protocol failure after the login is an AccessPoint problem and connects again after 2 s`() =
        runBlocking {
            val stack = stack()
            stack.loginStored()
            stack.accessPoint.sendBrokenPacket()
            stack.accessPoint.awaitConnections(2)
            stack.awaitOnline()
            assertEquals(listOf<SpotifyProblem>(SpotifyProblem.AccessPoint), stack.awaitProblems(1))
            assertEquals(listOf(2_000L), stack.pauses.value)
        }

    @Test
    fun `a protocol failure before the login moves on to the next access point at once`() = runBlocking {
        val stack = stack()
        val forged = server().apply { breakSignature = true }
        stack.accessPoints = listOf(forged.address, stack.accessPoint.address)
        stack.loginStored()
        assertEquals(1, forged.connectionCount)
        assertEquals(emptyList<Long>(), stack.pauses.value)
        assertEquals(listOf<SpotifyProblem>(SpotifyProblem.AccessPoint), stack.awaitProblems(1))
    }

    @Test
    fun `a key not sent yet survives a dropped connection and is answered after reconnecting`() =
        runBlocking {
            val stack = stack()
            stack.loginStored()
            stack.accessPoint.loginGate = CountDownLatch(1)
            stack.accessPoint.dropConnections()
            stack.accessPoint.awaitPackets(FakeAccessPoint.LOGIN, 2)
            val key = async(Dispatchers.IO) { stack.session.audioKey(GID, FILE) }
            withTimeout(WAIT_MS) { stack.session.keysWaiting.first { it == 1 } }
            stack.accessPoint.dropConnections()
            stack.accessPoint.awaitPackets(FakeAccessPoint.LOGIN, 3)
            stack.accessPoint.loginGate.countDown()
            assertEquals(Bytes.of(ByteArray(16) { 0x5a }), withTimeout(WAIT_MS) { key.await() })
            assertEquals(1, stack.accessPoint.keyPackets().size)
            assertEquals(3, stack.accessPoint.connectionCount)
        }

    @Test
    fun `no key is asked before any login`() = runBlocking {
        val stack = stack()
        expectFailure<NotLoggedInException> { stack.session.audioKey(GID, FILE) }
        assertEquals(0, stack.accessPoint.connectionCount)
    }

    @Test
    fun `a new login replaces the previous one and its connection`() = runBlocking {
        val stack = stack()
        stack.loginStored()
        val other = Credentials("other", AuthenticationType.STORED_CREDENTIALS, Bytes.of(byteArrayOf(5)))
        stack.session.login(other)
        stack.accessPoint.awaitClosed(1)
        val online = SpotifyState.Online("other", "BY", "premium")
        withTimeout(WAIT_MS) { stack.session.state.first { it == online } }
        assertEquals(2, stack.accessPoint.connectionCount)
    }

    private fun stack(
        timing: SessionTiming = SessionTiming(keyRetryMs = 10),
        inUse: Boolean = true,
    ): SpotifyStack = SpotifyStack(timing, inUse).also { stacks += it }

    private fun server(): FakeAccessPointServer = FakeAccessPointServer().also { servers += it }

    private companion object {
        const val WAIT_MS = 10_000L
        const val QUIET_MS = 200L
        val GID: Bytes = Bytes.of(ByteArray(16) { (it + 1).toByte() })
        val FILE: Bytes = Bytes.of(ByteArray(20) { (0x40 + it).toByte() })
    }
}
