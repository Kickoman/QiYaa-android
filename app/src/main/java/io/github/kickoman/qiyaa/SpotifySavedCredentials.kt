package io.github.kickoman.qiyaa

import io.github.kickoman.qiyaa.data.SpotifyStore
import io.github.kickoman.qiyaa.spotify.SavedCredentials
import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials

class SpotifySavedCredentials(private val store: SpotifyStore) : SavedCredentials {
    override fun load(): Credentials? {
        val type = store.type()
        if (type == SpotifyStore.NO_TYPE) return null
        return Credentials(store.username(), AuthenticationType(type), Bytes.of(store.blob()))
    }

    override fun save(credentials: Credentials) {
        store.save(credentials.username, credentials.type.code, credentials.data.toByteArray())
    }

    override fun forget() {
        store.clear()
    }
}
