package io.github.kickoman.qiyaa.yandex

import io.github.kickoman.qiyaa.support.Spec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TrackLyricsTest {
    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `LYR-02 every supplement fixture gives the spec's text or error`() {
        server.start()
        val api = YandexApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))
        for (case in Spec.cases(ENDPOINT)) {
            val fixture = Spec.fixture(ENDPOINT, case)
            val expected = Spec.expected(ENDPOINT, case)
            server.enqueue(fixture.response())
            if (fixture.status == 200) {
                val text = runBlocking { api.lyricsText("33311009:3720765") }
                assertEquals(case, expected.getValue("lyrics"), text?.let(::JsonPrimitive) ?: JsonNull)
            } else {
                val error = expected.getValue("error").jsonObject
                try {
                    runBlocking { api.lyricsText("33311009") }
                    fail("$case: no error")
                } catch (failed: HttpException) {
                    assertEquals(case, error.getValue("status").jsonPrimitive.int, failed.status)
                    assertEquals(case, error.getValue("message").jsonPrimitive.content, failed.reason)
                }
            }
            assertEquals("/tracks/33311009/supplement", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test
    fun `LYR-02 an empty text is none, and hasRights only refuses when it is false`() {
        assertEquals(null, TrackLyrics.textOf(JsonNull))
        val withoutRightsField = parseJsonObjectOrNull("""{"lyrics": {"fullLyrics": "words"}}""")!!
        assertEquals("words", TrackLyrics.textOf(withoutRightsField))
        val empty = parseJsonObjectOrNull("""{"lyrics": {"fullLyrics": "", "hasRights": true}}""")!!
        assertEquals(null, TrackLyrics.textOf(empty))
    }

    private companion object {
        const val ENDPOINT = "tracks-supplement"
    }
}
