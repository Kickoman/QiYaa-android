package io.github.kickoman.qiyaa.yandex

import io.github.kickoman.qiyaa.support.Fixture
import io.github.kickoman.qiyaa.support.Spec
import io.github.kickoman.qiyaa.support.SpecJson
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

class YandexApiTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, MockResponse>()
    private val requests = HashMap<String, MutableList<RecordedRequest>>()
    private val bodies = HashMap<RecordedRequest, String>()
    private lateinit var api: YandexApi
    private lateinit var library: Library

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.getOrPut(path) { ArrayList() } += request
                    bodies[request] = request.body.clone().readUtf8()
                    return routes["${request.method} $path"]?.clone()
                        ?: Spec.fixture("rotor-session-feedback", "404-not-found").response()
                }
            }
        server.start()
        api = YandexApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))
        api.token = "test-token"
        library = Library(api)
        route("GET", "/account/status", Spec.fixture("account-status", "ok"))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `account status parses to the spec's account and sends the OAuth header`() = runBlocking {
        val account = library.connectAccount()
        val actual =
            buildJsonObject {
                put("uid", account.uid)
                put("login", account.login)
                put("displayName", account.displayName)
            }
        assertEquals(Spec.expected("account-status", "ok"), actual)
        assertTrue(library.isLoggedIn)
        assertEquals("OAuth test-token", last("/account/status").getHeader("Authorization"))
        assertEquals("ru", last("/account/status").getHeader("Accept-Language"))
    }

    @Test
    fun `liked track ids match the spec and are fetched with POST tracks`() = runBlocking {
        library.connectAccount()
        route("POST", "/tracks/", Spec.fixture("tracks", "two-tracks"))
        for (case in Spec.cases("users-likes-tracks")) {
            route("GET", "/users/42/likes/tracks", Spec.fixture("users-likes-tracks", case))
            val expected = Spec.expected("users-likes-tracks", case)
            library.likedTracks()
            val ids = form(last("/tracks/"))["track-ids"].orEmpty().split(',')
            assertEquals("users-likes-tracks/$case", expected, SpecJson.ids("trackIds", ids))
            assertEquals("false", form(last("/tracks/"))["with-positions"])
            assertTrue(ids.all(library::isLiked))
            assertFalse(library.isLiked("0"))
        }
    }

    @Test
    fun `user playlists match the spec and entries without a kind are dropped`() = runBlocking {
        library.connectAccount()
        route("GET", "/users/42/playlists/list", Spec.fixture("users-playlists-list", "ok"))
        assertEquals(
            Spec.expected("users-playlists-list", "ok"),
            SpecJson.playlists(library.userPlaylists()),
        )
    }

    @Test
    fun `a playlist with embedded tracks parses them directly`() = runBlocking {
        route("GET", "/users/42/playlists/1003", Spec.fixture("users-playlists", "embedded-tracks"))
        val tracks = library.playlistTracks(PlaylistRef("42", "1003", "Дорога", 2))
        assertEquals(
            SpecJson.expectedTracks(Spec.expected("users-playlists", "embedded-tracks")),
            SpecJson.tracks(tracks),
        )
        assertEquals(null, requests["/tracks/"])
    }

    @Test
    fun `a playlist of ids only fetches the spec's ids with POST tracks`() = runBlocking {
        route("GET", "/users/42/playlists/1003", Spec.fixture("users-playlists", "ids-only"))
        route("POST", "/tracks/", Spec.fixture("tracks", "two-tracks"))
        library.playlistTracks(PlaylistRef("42", "1003", "Дорога", 2))
        val ids = form(last("/tracks/"))["track-ids"].orEmpty().split(',')
        assertEquals(Spec.expected("users-playlists", "ids-only"), SpecJson.ids("trackIds", ids))
    }

    @Test
    fun `liked artists match the spec, unwrapping artist objects`() = runBlocking {
        library.connectAccount()
        route("GET", "/users/42/likes/artists", Spec.fixture("users-likes-artists", "ok"))
        assertEquals(
            Spec.expected("users-likes-artists", "ok"),
            SpecJson.named("artists", library.likedArtists()),
        )
    }

    @Test
    fun `an artist's top tracks request the spec's ids, at most 100`() = runBlocking {
        route("POST", "/tracks/", Spec.fixture("tracks", "two-tracks"))
        for (case in Spec.cases("artists-track-ids-by-rating")) {
            route(
                "GET",
                "/artists/9/track-ids-by-rating",
                Spec.fixture("artists-track-ids-by-rating", case),
            )
            library.artistTopTracks("9")
            val ids = form(last("/tracks/"))["track-ids"].orEmpty().split(',')
            assertEquals(
                "artists-track-ids-by-rating/$case",
                Spec.expected("artists-track-ids-by-rating", case),
                SpecJson.ids("trackIds", ids),
            )
        }
    }

    @Test
    fun `liked albums request the spec's ids and leave podcasts out`() = runBlocking {
        library.connectAccount()
        route("GET", "/users/42/likes/albums", Spec.fixture("users-likes-albums", "ok"))
        route("POST", "/albums", Spec.fixture("albums", "with-podcast"))
        val albums = library.likedAlbums()
        val ids = form(last("/albums"))["album-ids"].orEmpty().split(',')
        assertEquals(Spec.expected("users-likes-albums", "ok"), SpecJson.ids("albumIds", ids))
        assertEquals(Spec.expected("albums", "with-podcast"), SpecJson.named("albums", albums))
    }

    @Test
    fun `album tracks join the volumes and fill in the album id`() = runBlocking {
        route("GET", "/albums/4053/with-tracks", Spec.fixture("albums-with-tracks", "two-volumes"))
        val tracks = library.albumTracks("4053")
        assertEquals(
            SpecJson.expectedTracks(Spec.expected("albums-with-tracks", "two-volumes")),
            SpecJson.tracks(tracks),
        )
    }

    @Test
    fun `stations match the spec and are requested in Russian`() = runBlocking {
        route("GET", "/rotor/stations/list", Spec.fixture("rotor-stations-list", "ok"))
        assertEquals(Spec.expected("rotor-stations-list", "ok"), SpecJson.stations(library.stations()))
        assertEquals("ru", last("/rotor/stations/list").requestUrl!!.queryParameter("language"))
    }

    @Test
    fun `a new wave and its next batch match the spec`() = runBlocking {
        route("POST", "/rotor/session/new", Spec.fixture("rotor-session-new", "ok"))
        val first = library.startWave(listOf("user:onyourwave"))
        assertEquals(
            SpecJson.expectedTracks(Spec.expected("rotor-session-new", "ok")),
            SpecJson.wave(first),
        )
        val body = Json.parseToJsonElement(bodyOf(last("/rotor/session/new"))) as JsonObject
        assertEquals("user:onyourwave", body["seeds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("true", body["includeTracksInResponse"]!!.jsonPrimitive.content)
        assertEquals(
            "application/json",
            last("/rotor/session/new").getHeader("Content-Type")?.substringBefore(';'),
        )

        route(
            "POST",
            "/rotor/session/${first.sessionId}/tracks",
            Spec.fixture("rotor-session-tracks", "ok"),
        )
        val more = library.moreWave(first.sessionId, listOf("38634572"))
        assertEquals(
            SpecJson.expectedTracks(Spec.expected("rotor-session-tracks", "ok")),
            SpecJson.wave(more),
        )
        val moreBody = Json.parseToJsonElement(
            bodyOf(last("/rotor/session/${first.sessionId}/tracks")),
        ) as JsonObject
        assertEquals("38634572", moreBody["queue"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `search results match the spec for artist, album, track, playlist and no best result`() =
        runBlocking {
            for (case in listOf("best-artist", "best-album", "best-track", "best-playlist", "no-best")) {
                route("GET", "/search", Spec.fixture("search", case))
                val result = library.search("кино")
                assertEquals(
                    "search/$case",
                    SpecJson.expectedTracks(Spec.expected("search", case)),
                    SpecJson.search(result),
                )
            }
            val url = last("/search").requestUrl!!
            assertEquals("кино", url.queryParameter("text"))
            assertEquals("all", url.queryParameter("type"))
            assertEquals("0", url.queryParameter("page"))
        }

    @Ignore("Known divergence Kickoman/QiYaa-android#34: bestType keeps the raw type")
    @Test
    fun `a best result of another type is reported as other`() = runBlocking {
        route("GET", "/search", Spec.fixture("search", "best-podcast"))
        val result = library.search("кино")
        assertEquals(
            SpecJson.expectedTracks(Spec.expected("search", "best-podcast")),
            SpecJson.search(result),
        )
    }

    @Test
    fun `likes, unlikes and dislikes post the track id and update the liked set`() = runBlocking {
        library.connectAccount()
        route(
            "POST",
            "/users/42/likes/tracks/add-multiple",
            Spec.fixture("users-likes-tracks-add-multiple", "ok"),
        )
        route("POST", "/users/42/likes/tracks/remove", Spec.fixture("users-likes-tracks-remove", "ok"))
        route(
            "POST",
            "/users/42/dislikes/tracks/add-multiple",
            Spec.fixture("users-dislikes-tracks-add-multiple", "ok"),
        )
        library.setLiked("77", true)
        assertTrue(library.isLiked("77"))
        assertEquals("77", form(last("/users/42/likes/tracks/add-multiple"))["track-ids"])
        library.setLiked("77", false)
        assertFalse(library.isLiked("77"))
        assertEquals("77", form(last("/users/42/likes/tracks/remove"))["track-ids"])
        library.dislike("1")
        assertEquals("1", form(last("/users/42/dislikes/tracks/add-multiple"))["track-ids"])
    }

    @Test
    fun `every error body form gives the spec's status and message`() = runBlocking {
        library.connectAccount()
        val cases =
            listOf(
                "users-likes-artists" to "401-session-expired",
                "account-status" to "500-empty",
                "rotor-session-feedback" to "404-not-found",
                "rotor-session-feedback" to "503-string-error",
            )
        for ((endpoint, case) in cases) {
            route("GET", "/users/42/likes/artists", Spec.fixture(endpoint, case))
            val expected = Spec.expected(endpoint, case).getValue("error").jsonObject
            try {
                library.likedArtists()
                fail("$endpoint/$case: expected HttpException")
            } catch (failed: HttpException) {
                assertEquals(
                    "$endpoint/$case",
                    expected.getValue("status").jsonPrimitive.int,
                    failed.status,
                )
                SpecJson.string(expected["message"])?.let { message ->
                    assertTrue("$endpoint/$case: ${failed.message}", failed.message!!.contains(message))
                }
            }
        }
    }

    @Test
    fun `a 2xx body without result is a MalformedResponseException`() = runBlocking {
        library.connectAccount()
        route("GET", "/users/42/likes/albums", Spec.fixture("oauth-token", "ok"))
        try {
            library.likedAlbums()
            fail("expected MalformedResponseException")
        } catch (failed: MalformedResponseException) {
            assertEquals("GET /users/42/likes/albums: no \"result\" in the response", failed.message)
        }
    }

    @Test
    fun `an account without uid is an AuthException`() = runBlocking {
        route("GET", "/account/status", Spec.fixture("play-audio", "ok"))
        try {
            library.connectAccount()
            fail("expected AuthException")
        } catch (failed: AuthException) {
            assertTrue(failed.message!!.contains("/account/status"))
        }
    }

    @Test
    fun `401 and 403 from any API endpoint are reported as token rejections`() = runBlocking {
        library.connectAccount()
        val seen = ArrayList<HttpException>()
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                api.tokenRejections.collect {
                    seen +=
                        it
                }
            }
        val rejected = Spec.fixture("users-likes-artists", "401-session-expired")
        route("GET", "/users/42/likes/artists", rejected)
        route("POST", "/tracks/", rejected.withStatus(403))
        ignoreFailure { library.likedArtists() }
        ignoreFailure { api.tracks(listOf("1")) }
        collector.cancel()
        assertEquals(listOf(401, 403), seen.map { it.status })
        assertEquals("/tracks/", seen[1].path)
    }

    @Test
    fun `server errors, storage hosts and requests without a token are not token rejections`() = runBlocking {
        library.connectAccount()
        val seen = ArrayList<HttpException>()
        val collector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                api.tokenRejections.collect {
                    seen +=
                        it
                }
            }
        route("GET", "/users/42/likes/artists", Spec.fixture("account-status", "500-empty"))
        ignoreFailure { library.likedArtists() }
        route("GET", "/download-info/xyz", Spec.fixture("account-status", "500-empty").withStatus(403))
        ignoreFailure { api.getText(server.url("/download-info/xyz").toString()) }
        api.token = ""
        route("GET", "/account/status", Spec.fixture("users-likes-artists", "401-session-expired"))
        ignoreFailure { api.accountStatus() }
        collector.cancel()
        assertEquals(emptyList<HttpException>(), seen)
    }

    @Test
    fun `user endpoints refuse to run before the account is known`() = runBlocking {
        try {
            library.likedArtists()
            fail("expected NotSignedInException")
        } catch (expected: NotSignedInException) {
            assertTrue(expected.message!!.contains("likes/artists"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `resolveTrackUrl takes two hops, picks the spec's variant and signs the link`() = runBlocking {
        val storage = server.url("/storage").toString()
        val variants = Spec.fixture("tracks-download-info", "variants")
        route(
            "GET",
            "/tracks/1/download-info",
            variants.copy(body = variants.body.replace("https://storage.mds.yandex.net", storage)),
        )
        val expectedBest = Spec.expected(
            "tracks-download-info",
            "variants",
        ).getValue("best").jsonPrimitive.content
        val bestPath =
            "/storage" + expectedBest.removePrefix("https://storage.mds.yandex.net").substringBefore('?')
        route("GET", bestPath, Spec.fixture("storage-download-info", "ok"))

        val resolved = api.resolveTrackUrl("1:99")

        assertEquals(
            Spec.expected("storage-download-info", "ok").getValue("trackUrl").jsonPrimitive.content,
            resolved.url,
        )
        assertEquals(320, resolved.bitrateKbps)
        assertEquals("json", last(bestPath).requestUrl!!.queryParameter("format"))
    }

    @Test
    fun `reportPlayStarted sends the Winamp play-audio fields`() = runBlocking {
        val account = library.connectAccount()
        route("POST", "/play-audio", Spec.fixture("play-audio", "ok"))
        api.reportPlayStarted(account, Track("1", "T", listOf("A"), "10", 180000), "PLAY-ID")
        val sent = form(last("/play-audio"))
        assertEquals("1", sent["track-id"])
        assertEquals("10", sent["album-id"])
        assertEquals("web-own_tracks-track-track-main", sent["from"])
        assertEquals("PLAY-ID", sent["play-id"])
        assertEquals("42", sent["uid"])
        assertEquals("180", sent["track-length-seconds"])
        assertEquals("0", sent["total-played-seconds"])
        assertTrue(sent["timestamp"]!!.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
        assertEquals(sent["timestamp"], sent["client-now"])
    }

    @Test
    fun `tracksByIds asks for at most 250 ids per request, in order`() = runBlocking {
        route("POST", "/tracks/", Spec.fixture("tracks", "two-tracks"))
        val ids = (1..300).map { it.toString() }
        library.tracksByIds(ids)
        val chunks = requests.getValue("/tracks/").map { form(it)["track-ids"].orEmpty().split(',') }
        assertEquals(listOf(250, 50), chunks.map { it.size })
        assertEquals(ids, chunks.flatten())
    }

    private fun route(method: String, path: String, fixture: Fixture) {
        routes["$method $path"] = fixture.response()
    }

    private fun Fixture.withStatus(status: Int) = copy(status = status)

    private suspend fun ignoreFailure(call: suspend () -> Unit) {
        try {
            call()
        } catch (ignored: YandexException) {
            // The test looks at the emitted rejections, not at the thrown error.
        }
    }

    private fun last(path: String) = requests[path]?.last() ?: error("no request to $path")

    private fun bodyOf(request: RecordedRequest): String = bodies[request].orEmpty()

    private fun form(request: RecordedRequest): Map<String, String> = bodyOf(request)
        .split('&')
        .filter { it.isNotEmpty() }
        .associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
}
