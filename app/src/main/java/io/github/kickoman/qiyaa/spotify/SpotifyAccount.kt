package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.LoginError
import kotlinx.coroutines.flow.StateFlow

interface SavedCredentials {
    fun load(): Credentials?

    fun save(credentials: Credentials)

    fun forget()
}

class SpotifyAccount(private val session: SpotifySession, private val saved: SavedCredentials) {
    val state: StateFlow<SpotifyState> get() = session.state

    private val listener =
        object : SessionListener {
            override fun loggedIn(reusable: Credentials) {
                if (reusable.isReusable() && saved.load() != reusable) saved.save(reusable)
            }

            override fun refused(error: LoginError) {
                if (error == LoginError.BAD_CREDENTIALS) saved.forget()
            }
        }

    fun hasSavedLogin(): Boolean = saved.load()?.isReusable() == true

    fun start() {
        val credentials = saved.load()?.takeIf { it.isReusable() } ?: return
        session.login(credentials, listener)
    }

    fun login(accessToken: String) {
        require(accessToken.isNotEmpty()) { "an access token is not empty" }
        session.login(Credentials.fromAccessToken(accessToken), listener)
    }

    fun logout() {
        saved.forget()
        session.logout()
        saved.forget()
    }

    private fun Credentials.isReusable(): Boolean =
        type == AuthenticationType.STORED_CREDENTIALS && username.isNotEmpty() && !data.isEmpty()
}
