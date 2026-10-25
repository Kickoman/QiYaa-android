package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.LoginError
import io.github.kickoman.qiyaa.support.SpotifyStack
import io.github.kickoman.qiyaa.support.expectFailure
import io.github.kickoman.qiyaa.support.fieldOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAccountTest {
    private val stack = SpotifyStack()
    private val saved = MemoryCredentials()
    private val account = SpotifyAccount(stack.session, saved)

    @After
    fun tearDown() = stack.close()

    @Test
    fun `nothing happens without saved credentials`() = runBlocking {
        assertFalse(account.hasSavedLogin())
        account.start()
        delay(QUIET_MS)
        assertEquals(SpotifyState.LoggedOut, account.state.value)
        assertEquals(0, stack.accessPoint.connectionCount)
    }

    @Test
    fun `SPOT-04 saved credentials log in without a dialog and are kept up to date`() = runBlocking {
        saved.credentials = STORED
        stack.accessPoint.welcomeBlob = byteArrayOf(5, 6)
        assertTrue(account.hasSavedLogin())
        account.start()
        assertEquals(SpotifyState.Online("owner", "BY", "premium"), stack.awaitOnline())
        val login = fieldOf(stack.accessPoint.logins().single().payload, 10)
        assertEquals(listOf<Byte>(1, 2, 3), fieldOf(login, 30).toList())
        assertEquals(Bytes.of(byteArrayOf(5, 6)), saved.credentials!!.data)
        assertEquals(1, saved.saves.size)
    }

    @Test
    fun `the same reusable credentials are not written again`() = runBlocking {
        stack.accessPoint.welcomeBlob = byteArrayOf(1, 2, 3)
        saved.credentials = STORED
        account.start()
        stack.awaitOnline()
        assertEquals(0, saved.saves.size)
    }

    @Test
    fun `SPOT-03 a token login saves the reusable credentials and never the token`() = runBlocking {
        stack.accessPoint.welcomeBlob = byteArrayOf(7, 7)
        account.login("BQ-token")
        stack.awaitOnline()
        assertEquals(
            Credentials("owner", AuthenticationType.STORED_CREDENTIALS, Bytes.of(byteArrayOf(7, 7))),
            saved.credentials,
        )
        assertTrue(saved.saves.none { it.type == AuthenticationType.SPOTIFY_TOKEN })
        assertTrue(account.hasSavedLogin())
    }

    @Test
    fun `SPOT-04 BadCredentials deletes the saved credentials and the login has expired`() = runBlocking {
        saved.credentials = STORED
        stack.accessPoint.loginRefusal = LoginError.BAD_CREDENTIALS.code
        account.start()
        stack.awaitState(SpotifyState.Expired)
        assertNull(saved.credentials)
        assertFalse(account.hasSavedLogin())
        expectFailure<NotLoggedInException> { stack.session.audioKey(GID, FILE) }
        Unit
    }

    @Test
    fun `SPOT-04 a network failure keeps the saved credentials and tries again`() = runBlocking {
        saved.credentials = STORED
        stack.accessPoints = listOf(stack.refusingAddress())
        stack.maxPauses = 1
        account.start()
        stack.awaitPauses(1)
        assertEquals(STORED, saved.credentials)
        assertEquals(SpotifyState.Offline, account.state.value)
    }

    @Test
    fun `SPOT-05 an account without Premium saves nothing`() = runBlocking {
        stack.accessPoint.loginRefusal = LoginError.PREMIUM_ACCOUNT_REQUIRED.code
        account.login("BQ-token")
        stack.awaitState(SpotifyState.PremiumRequired)
        assertNull(saved.credentials)
        assertEquals(0, saved.saves.size)
    }

    @Test
    fun `SPOT-05 a saved login refused for Premium keeps its credentials for the next start`() = runBlocking {
        saved.credentials = STORED
        stack.accessPoint.loginRefusal = LoginError.PREMIUM_ACCOUNT_REQUIRED.code
        account.start()
        stack.awaitState(SpotifyState.PremiumRequired)
        assertEquals(STORED, saved.credentials)
        assertEquals(0, saved.forgets)
    }

    @Test
    fun `SPOT-06 logout deletes the saved credentials and closes the access point connection`() =
        runBlocking {
            saved.credentials = STORED
            account.start()
            stack.awaitOnline()
            account.logout()
            assertNull(saved.credentials)
            assertFalse(account.hasSavedLogin())
            assertEquals(SpotifyState.LoggedOut, account.state.value)
            stack.accessPoint.awaitClosed(1)
            expectFailure<NotLoggedInException> { stack.session.audioKey(GID, FILE) }
            delay(QUIET_MS)
            assertNull(saved.credentials)
            assertEquals(1, stack.accessPoint.connectionCount)
        }

    @Test
    fun `SPOT-06 logout forgets the saved login before the state says logged out`() = runBlocking {
        saved.credentials = STORED
        account.start()
        stack.awaitOnline()
        val statesAtForget = mutableListOf<SpotifyState>()
        saved.onForget = { statesAtForget += account.state.value }

        account.logout()

        assertTrue(statesAtForget.toString(), statesAtForget.first() is SpotifyState.Online)
        assertEquals(SpotifyState.LoggedOut, statesAtForget.last())
        assertNull(saved.credentials)
    }

    @Test
    fun `saved credentials of another type are no saved login`() = runBlocking {
        saved.credentials = Credentials.fromAccessToken("BQ-token")
        assertFalse(account.hasSavedLogin())
        account.start()
        delay(QUIET_MS)
        assertEquals(0, stack.accessPoint.connectionCount)
    }

    private class MemoryCredentials : SavedCredentials {
        @Volatile
        var credentials: Credentials? = null
        val saves = mutableListOf<Credentials>()

        @Volatile
        var forgets = 0

        @Volatile
        var onForget: () -> Unit = {}

        override fun load(): Credentials? = credentials

        override fun save(credentials: Credentials) {
            synchronized(saves) { saves += credentials }
            this.credentials = credentials
        }

        override fun forget() {
            onForget()
            forgets++
            credentials = null
        }
    }

    private companion object {
        const val QUIET_MS = 200L
        val STORED = SpotifyStack.STORED
        val GID: Bytes = Bytes.of(ByteArray(16))
        val FILE: Bytes = Bytes.of(ByteArray(20))
    }
}
