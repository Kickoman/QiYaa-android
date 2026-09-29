package io.github.kickoman.qiyaa.yandex

import io.github.kickoman.qiyaa.support.Spec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WaveFeedbackTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, MockResponse>()
    private val seen = ArrayList<Pair<String, String>>()
    private lateinit var library: Library

    private val context = WaveContext(sessionId = "S1", stationId = "user:onyourwave", batchId = "B1")
    private val track = Track(id = "38634572", title = "T", albumId = "4053")

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val target = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                    seen += target to request.body.readUtf8()
                    return routes[url.encodedPath]?.clone() ?: MockResponse().setResponseCode(599)
                }
            }
        server.start()
        library = Library(YandexApi(OkHttpClient(), server.url("/").toString().removeSuffix("/")))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `TRK-03 radioStarted carries from and no trackId`() = runBlocking {
        routes["/rotor/session/S1/feedback"] = Spec.fixture("rotor-session-feedback", "ok").response()
        library.waveFeedback(context, WaveEvent.RADIO_STARTED, track = null, playedSeconds = 0.0)
        val event = event(seen.single())
        assertEquals("radioStarted", event.string("type"))
        assertEquals("web-main-rup-radio-main", event.string("from"))
        assertFalse(event.containsKey("trackId"))
        assertFalse(event.containsKey("totalPlayedSeconds"))
        assertTrue(event.string("timestamp").matches(TIMESTAMP))
    }

    @Test
    fun `TRK-04 trackStarted names the track with its album and has no played seconds`() = runBlocking {
        routes["/rotor/session/S1/feedback"] = Spec.fixture("rotor-session-feedback", "ok").response()
        library.waveFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
        library.waveFeedback(context, WaveEvent.TRACK_STARTED, track.copy(albumId = ""), 0.0)
        assertEquals("38634572:4053", event(seen[0]).string("trackId"))
        assertEquals("38634572", event(seen[1]).string("trackId"))
        assertFalse(event(seen[0]).containsKey("totalPlayedSeconds"))
        assertFalse(event(seen[0]).containsKey("from"))
    }

    @Test
    fun `TRK-05 TRK-06 TRK-08 finished and skip carry played seconds rounded to a tenth`() = runBlocking {
        routes["/rotor/session/S1/feedback"] = Spec.fixture("rotor-session-feedback", "ok").response()
        library.waveFeedback(context, WaveEvent.TRACK_FINISHED, track, 224.96)
        library.waveFeedback(context, WaveEvent.SKIP, track, 12.34)
        assertEquals("trackFinished", event(seen[0]).string("type"))
        assertEquals(225.0, event(seen[0]).getValue("totalPlayedSeconds").jsonPrimitive.double, 0.0)
        assertEquals("skip", event(seen[1]).string("type"))
        assertEquals(12.3, event(seen[1]).getValue("totalPlayedSeconds").jsonPrimitive.double, 0.0)
    }

    @Test
    fun `TRK-09 the session endpoint gets the event and the batch id`() = runBlocking {
        routes["/rotor/session/S1/feedback"] = Spec.fixture("rotor-session-feedback", "ok").response()
        library.waveFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
        library.waveFeedback(context.copy(batchId = ""), WaveEvent.TRACK_STARTED, track, 0.0)
        val (target, body) = seen[0]
        assertEquals("/rotor/session/S1/feedback", target)
        assertEquals("B1", json(body).string("batchId"))
        assertTrue(json(body).containsKey("event"))
        assertFalse("an empty batch id is left out", json(seen[1].second).containsKey("batchId"))
    }

    @Test
    fun `TRK-10 a 4xx sends the bare event to the station and later events go straight there`() =
        runBlocking {
            routes["/rotor/session/S1/feedback"] =
                Spec.fixture("rotor-session-feedback", "404-not-found").response()
            routes["/rotor/station/user:onyourwave/feedback"] =
                Spec.fixture("rotor-station-feedback", "ok").response()
            library.waveFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
            library.waveFeedback(context, WaveEvent.SKIP, track, 3.0)
            assertEquals(
                listOf(
                    "/rotor/session/S1/feedback",
                    "/rotor/station/user:onyourwave/feedback?batch-id=B1",
                    "/rotor/station/user:onyourwave/feedback?batch-id=B1",
                ),
                seen.map { it.first },
            )
            assertEquals("trackStarted", json(seen[1].second).string("type"))
            assertEquals("skip", json(seen[2].second).string("type"))
        }

    @Test
    fun `TRK-10 without a station id nothing more is sent after a 4xx`() = runBlocking {
        routes["/rotor/session/S1/feedback"] =
            Spec.fixture("rotor-session-feedback", "404-not-found").response()
        library.waveFeedback(context.copy(stationId = ""), WaveEvent.TRACK_STARTED, track, 0.0)
        assertEquals(listOf("/rotor/session/S1/feedback"), seen.map { it.first })
    }

    @Test
    fun `TRK-11 a 5xx is not retried and has no fallback`() = runBlocking {
        routes["/rotor/session/S1/feedback"] =
            Spec.fixture("rotor-session-feedback", "503-string-error").response()
        library.waveFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
        library.waveFeedback(context, WaveEvent.SKIP, track, 1.0)
        assertEquals(
            listOf("/rotor/session/S1/feedback", "/rotor/session/S1/feedback"),
            seen.map {
                it.first
            },
        )
    }

    @Test
    fun `TRK-11 a network failure is swallowed`() = runBlocking {
        server.shutdown()
        library.waveFeedback(context, WaveEvent.TRACK_STARTED, track, 0.0)
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun event(request: Pair<String, String>): JsonObject =
        json(request.second).getValue("event").jsonObject

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        val TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
    }
}
