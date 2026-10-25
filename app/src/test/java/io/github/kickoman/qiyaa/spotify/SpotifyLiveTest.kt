package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.ExtensionKind
import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.Metadata
import io.github.kickoman.qiyaa.spotify.protocol.ProtoReader
import io.github.kickoman.qiyaa.support.fieldOf
import io.github.kickoman.qiyaa.support.varintOf
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpotifyLiveTest {
    @Test
    fun `logs in to the real Spotify with saved credentials and gets a track's audio key`() = runBlocking {
        val given = System.getenv(CREDENTIALS).orEmpty()
        assumeTrue("$CREDENTIALS is not set", given.isNotEmpty())
        val credentials =
            credentialsOf(if (given.trimStart().startsWith("{")) given else File(given).readText())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient()
        val release = System.getProperty("os.version").orEmpty()
        val identity = SpotifyIdentity("0.0.0-live", "d".repeat(40), release, "16", "arm64-v8a")
        val always = MutableStateFlow(true)
        val session = SpotifySession(client, identity, always, always, scope, log = ::println)
        val web = SpotifyWeb(client, session, identity, log = ::println)
        try {
            session.login(credentials)
            val state = withTimeout(LOGIN_MS) {
                session.state.first {
                    it is SpotifyState.Online || it in ENDINGS || it is SpotifyState.Refused
                }
            }
            assertTrue("$state", state is SpotifyState.Online)
            assertEquals(credentials.username, (state as SpotifyState.Online).username)
            val track = System.getenv(TRACK)?.takeIf(String::isNotEmpty) ?: DEFAULT_TRACK
            val metadata = web.spclient(
                WebRequest(
                    "/extended-metadata/v0/extended-metadata",
                    "POST",
                    Bytes.of(Metadata.request(listOf(Ids.trackUri(track)!!), ExtensionKind.TRACK_V4)),
                ),
            )
            val file = oggFileOf(metadata)
            val key = withTimeout(LOGIN_MS) { session.audioKey(Ids.gidFromBase62(track)!!, file) }
            assertEquals(16, key.size)
            println("$track: logged in, country ${session.country}, an audio key of ${key.size} bytes")
        } finally {
            session.logout()
            scope.cancel()
        }
    }

    private fun credentialsOf(text: String): Credentials {
        val json = Json.parseToJsonElement(text).jsonObject
        return Credentials(
            json.getValue("username").jsonPrimitive.content,
            AuthenticationType(json.getValue("type").jsonPrimitive.int),
            Bytes.of(Base64.getDecoder().decode(json.getValue("data").jsonPrimitive.content)),
        )
    }

    private fun oggFileOf(metadata: ByteArray): Bytes {
        val track = fieldOf(fieldOf(fieldOf(fieldOf(metadata, 2), 3), 3), 2)
        val files = ProtoReader(track)
        while (files.next()) {
            if (files.field != TRACK_FILES) continue
            val file = files.bytes()
            if ((varintOf(file, 2) ?: 0L) in OGG_VORBIS) return Bytes.of(fieldOf(file, 1))
        }
        throw AssertionError("no Ogg Vorbis file in the metadata of ${metadata.size} bytes")
    }

    private companion object {
        const val CREDENTIALS = "QIYAA_SPOTIFY_CREDENTIALS"
        const val TRACK = "QIYAA_SPOTIFY_TRACK"
        const val DEFAULT_TRACK = "6PCoZdjSPbRMehHviTrpB3"
        const val LOGIN_MS = 20_000L
        const val TRACK_FILES = 12
        val OGG_VORBIS = 0L..2L
        val ENDINGS = setOf(SpotifyState.Expired, SpotifyState.PremiumRequired, SpotifyState.LoggedOut)
    }
}
