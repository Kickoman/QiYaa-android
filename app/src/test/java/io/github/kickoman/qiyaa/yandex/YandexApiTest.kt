package io.github.kickoman.qiyaa.yandex

import java.net.URLDecoder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class YandexApiTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, MockResponse>()
    private val requests = HashMap<String, RecordedRequest>()
    private val bodies = HashMap<String, String>()
    private lateinit var api: YandexApi
    private lateinit var library: Library

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests[path] = request
                    bodies[path] = request.body.clone().readUtf8()
                    return routes["${request.method} $path"]
                        ?: MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        server.start()
        api = YandexApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))
        api.token = "test-token"
        library = Library(api)
        result("GET", "/account/status", """{"account":{"uid":42,"login":"kick","displayName":"Kick"}}""")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `connectAccount sends the OAuth header and Accept-Language ru`() = runBlocking {
        val account = library.connectAccount()
        assertEquals("42", account.uid)
        assertTrue(library.isLoggedIn)
        assertEquals("OAuth test-token", last("/account/status").getHeader("Authorization"))
        assertEquals("ru", last("/account/status").getHeader("Accept-Language"))
    }

    @Test
    fun `likedTracks fetches metadata by ids and remembers the likes`() = runBlocking {
        library.connectAccount()
        result(
            "GET",
            "/users/42/likes/tracks",
            """{"library":{"uid":42,"tracks":[{"id":"1","albumId":"10"},{"id":"2","albumId":"20"}]}}""",
        )
        result("POST", "/tracks/", "[" + trackJson(1, "One", 10) + "," + trackJson(2, "Two", 20) + "]")
        val tracks = library.likedTracks()
        assertEquals(2, tracks.size)
        assertEquals("Two", tracks[1].title)
        assertEquals("1,2", last("/tracks/").formValue("track-ids"))
        assertEquals("false", last("/tracks/").formValue("with-positions"))
        assertTrue(library.isLiked("1"))
        assertFalse(library.isLiked("3"))
    }

    @Test
    fun `playlistTracks uses embedded track objects when present`() = runBlocking {
        library.connectAccount()
        result(
            "GET",
            "/users/42/playlists/list",
            """[{"uid":42,"kind":1003,"title":"Дорога","trackCount":2}]""",
        )
        val lists = library.userPlaylists()
        assertEquals(1, lists.size)
        assertEquals("1003", lists[0].kind)
        assertEquals("Дорога", lists[0].title)
        assertEquals(2, lists[0].trackCount)
        result(
            "GET",
            "/users/42/playlists/1003",
            """{"tracks":[{"id":5,"track":${trackJson(5, "Five")}}]}""",
        )
        val tracks = library.playlistTracks(lists[0])
        assertEquals(listOf("Five"), tracks.map { it.title })
    }

    @Test
    fun `playlistTracks falls back to fetching by ids`() = runBlocking {
        result("GET", "/users/7/playlists/3", """{"tracks":[{"id":1},{"id":2}]}""")
        result("POST", "/tracks/", "[" + trackJson(1, "One") + "," + trackJson(2, "Two") + "]")
        val tracks = library.playlistTracks(PlaylistRef("7", "3", "x", 2))
        assertEquals(2, tracks.size)
        assertEquals("1,2", last("/tracks/").formValue("track-ids"))
    }

    @Test
    fun `likedArtists unwraps artist objects and artistTopTracks resolves ids`() = runBlocking {
        library.connectAccount()
        result(
            "GET",
            "/users/42/likes/artists",
            """[{"id":9,"name":"Кино"},{"artist":{"id":10,"name":"Земфира"}}]""",
        )
        val artists = library.likedArtists()
        assertEquals(listOf("9", "10"), artists.map { it.id })
        result("GET", "/artists/9/track-ids-by-rating", """{"artist":{},"tracks":["1","2"]}""")
        result("POST", "/tracks/", "[" + trackJson(1, "One") + "," + trackJson(2, "Two") + "]")
        assertEquals(2, library.artistTopTracks("9").size)
    }

    @Test
    fun `likedAlbums skips podcasts and albumTracks flattens volumes`() = runBlocking {
        library.connectAccount()
        result("GET", "/users/42/likes/albums", """[{"id":100},{"album":{"id":200}}]""")
        result(
            "POST",
            "/albums",
            """[{"id":100,"title":"Группа крови","artists":[{"name":"Кино"}]},{"id":200,"title":"Cast","type":"podcast"}]""",
        )
        val albums = library.likedAlbums()
        assertEquals(listOf(NamedRef("100", "Кино - Группа крови")), albums)
        assertEquals("100,200", last("/albums").formValue("album-ids"))
        result(
            "GET",
            "/albums/100/with-tracks",
            """{"volumes":[[${trackJson(1, "A")}],[${trackJson(2, "B")}]]}""",
        )
        val tracks = library.albumTracks("100")
        assertEquals(2, tracks.size)
        assertEquals("100", tracks[0].albumId)
    }

    @Test
    fun `stations are requested in Russian and keyed type colon tag`() = runBlocking {
        result(
            "GET",
            "/rotor/stations/list",
            """[{"station":{"id":{"type":"genre","tag":"rock"},"name":"Рок"}}]""",
        )
        val stations = library.stations()
        assertEquals(listOf(Station("genre:rock", "genre", "Рок")), stations)
        assertEquals("ru", last("/rotor/stations/list").requestUrl!!.queryParameter("language"))
    }

    @Test
    fun `startWave posts JSON seeds and moreWave keeps the session id`() = runBlocking {
        result(
            "POST",
            "/rotor/session/new",
            """{"radioSessionId":"S1","batchId":"B1","sequence":[{"type":"track","track":${trackJson(
                1,
                "W1",
            )}}]}""",
        )
        val first = library.startWave(listOf("user:onyourwave"))
        assertEquals("S1", first.sessionId)
        assertEquals(1, first.tracks.size)
        val body = Json.parseToJsonElement(last("/rotor/session/new").bodyText()) as JsonObject
        assertEquals("user:onyourwave", body["seeds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("true", body["includeTracksInResponse"]!!.jsonPrimitive.content)
        val contentType = last("/rotor/session/new").getHeader("Content-Type")?.substringBefore(';')
        assertEquals("application/json", contentType)

        result(
            "POST",
            "/rotor/session/S1/tracks",
            """{"batchId":"B2","sequence":[{"track":${trackJson(2, "W2")}}]}""",
        )
        val more = library.moreWave("S1", listOf("1"))
        assertEquals("W2", more.tracks[0].title)
        assertEquals("S1", more.sessionId)
        val moreBody = Json.parseToJsonElement(last("/rotor/session/S1/tracks").bodyText()) as JsonObject
        assertEquals("1", moreBody["queue"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `search returns the best artist and the track list`() = runBlocking {
        result(
            "GET",
            "/search",
            """{"best":{"type":"artist","result":{"id":9,"name":"Кино"}},"tracks":{"results":[${trackJson(
                3,
                "Кукушка",
            )}]}}""",
        )
        val found = library.search("кино")
        assertEquals("artist", found.bestType)
        assertEquals("9", found.bestId)
        assertEquals("Кино", found.bestName)
        assertEquals(1, found.tracks.size)
        val url = last("/search").requestUrl!!
        assertEquals("кино", url.queryParameter("text"))
        assertEquals("all", url.queryParameter("type"))
        assertEquals("0", url.queryParameter("page"))
    }

    @Test
    fun `setLiked and dislike update the liked set`() = runBlocking {
        library.connectAccount()
        result("POST", "/users/42/likes/tracks/add-multiple", """{"revision":1}""")
        result("POST", "/users/42/likes/tracks/remove", """{"revision":2}""")
        result("POST", "/users/42/dislikes/tracks/add-multiple", """{"revision":3}""")
        library.setLiked("77", true)
        assertTrue(library.isLiked("77"))
        assertEquals("77", last("/users/42/likes/tracks/add-multiple").formValue("track-ids"))
        library.setLiked("77", false)
        assertFalse(library.isLiked("77"))
        library.dislike("1")
        assertFalse(library.isLiked("1"))
    }

    @Test
    fun `HTTP errors carry the status, the request and the server message`() = runBlocking {
        library.connectAccount()
        json(
            "GET",
            "/users/42/likes/artists",
            """{"error":{"name":"session-expired","message":"Token expired"}}""",
            401,
        )
        try {
            library.likedArtists()
            fail("expected HttpException")
        } catch (failed: HttpException) {
            assertEquals(401, failed.status)
            assertTrue(failed.isTokenRejected)
            assertEquals("HTTP 401 on GET /users/42/likes/artists: Token expired", failed.message)
        }
    }

    @Test
    fun `a body without result is a MalformedResponseException`() = runBlocking {
        library.connectAccount()
        json("GET", "/users/42/likes/albums", """{"nope":1}""")
        try {
            library.likedAlbums()
            fail("expected MalformedResponseException")
        } catch (failed: MalformedResponseException) {
            assertEquals("GET /users/42/likes/albums: no \"result\" in the response", failed.message)
        }
    }

    @Test
    fun `an account without uid is an AuthException`() = runBlocking {
        result("GET", "/account/status", """{"account":{}}""")
        try {
            library.connectAccount()
            fail("expected AuthException")
        } catch (failed: AuthException) {
            assertTrue(failed.message!!.contains("/account/status"))
        }
    }

    @Test
    fun `resolveTrackUrl takes two hops and signs the link`() = runBlocking {
        val infoUrl = server.url("/download-info/xyz").toString()
        result(
            "GET",
            "/tracks/1/download-info",
            """[{"codec":"mp3","bitrateInKbps":192,"preview":false,"downloadInfoUrl":"$infoUrl"}]""",
        )
        json("GET", "/download-info/xyz", """{"host":"h.net","path":"/p/q","ts":"0005","s":"abc"}""")
        val resolved = api.resolveTrackUrl("1:99")
        assertEquals(192, resolved.bitrateKbps)
        assertTrue(resolved.url.startsWith("https://h.net/get-mp3/"))
        assertTrue(resolved.url.endsWith("/0005/p/q"))
        assertEquals("json", last("/download-info/xyz").requestUrl!!.queryParameter("format"))
    }

    @Test
    fun `reportPlayStarted sends the Winamp play-audio fields`() = runBlocking {
        val account = library.connectAccount()
        result("POST", "/play-audio", "\"ok\"")
        api.reportPlayStarted(account, Track("1", "T", listOf("A"), "10", 180000), "PLAY-ID")
        val body = last("/play-audio").bodyText()
        val form =
            body.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        assertEquals("1", form["track-id"])
        assertEquals("10", form["album-id"])
        assertEquals("web-own_tracks-track-track-main", form["from"])
        assertEquals("PLAY-ID", form["play-id"])
        assertEquals("42", form["uid"])
        assertEquals("180", form["track-length-seconds"])
        assertEquals("0", form["total-played-seconds"])
        assertTrue(form["timestamp"]!!.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
        assertEquals(form["timestamp"], form["client-now"])
    }

    @Test
    fun `tracksByIds fetches in chunks of 250 and keeps the order`() = runBlocking {
        var calls = 0
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    calls++
                    val encoded = request.body.readUtf8().substringAfter(
                        "track-ids=",
                    ).substringBefore('&')
                    val ids = URLDecoder.decode(encoded, "UTF-8").split(',')
                    val tracks = ids.joinToString(",") { trackJson(it.toInt(), "t$it") }
                    return MockResponse().setBody("""{"invocationInfo":{},"result":[$tracks]}""")
                }
            }
        val tracks = library.tracksByIds((1..300).map { it.toString() })
        assertEquals(300, tracks.size)
        assertEquals("t300", tracks.last().title)
        assertEquals(2, calls)
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
        json("GET", "/users/42/likes/artists", """{"error":{"message":"Token expired"}}""", 401)
        json("POST", "/tracks/", """{"error":"forbidden"}""", 403)
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
        json("GET", "/users/42/likes/artists", """{"error":"boom"}""", 500)
        ignoreFailure { library.likedArtists() }
        json("GET", "/download-info/xyz", "", 403)
        ignoreFailure { api.getText(server.url("/download-info/xyz").toString()) }
        api.token = ""
        json("GET", "/account/status", """{"error":"no token"}""", 401)
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

    private fun result(method: String, path: String, body: String) =
        json(method, path, """{"invocationInfo":{},"result":$body}""")

    private fun json(method: String, path: String, body: String, code: Int = 200) {
        routes["$method $path"] =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun trackJson(id: Int, title: String, album: Int = 0): String {
        var text = """{"id":$id,"title":"$title","artists":[{"name":"Artist"}],"durationMs":180000"""
        if (album != 0) text += ""","albums":[{"id":$album}]"""
        return "$text}"
    }

    private suspend fun ignoreFailure(call: suspend () -> Unit) {
        try {
            call()
        } catch (ignored: YandexException) {
            // The test looks at the emitted rejections, not at the thrown error.
        }
    }

    private fun last(path: String) = requests[path] ?: error("no request to $path")

    private fun RecordedRequest.bodyText(): String = bodies[requestUrl!!.encodedPath].orEmpty()

    private fun RecordedRequest.formValue(key: String): String = bodyText()
        .split('&')
        .map { it.split('=', limit = 2) }
        .firstOrNull { URLDecoder.decode(it[0], "UTF-8") == key }
        ?.let { URLDecoder.decode(it[1], "UTF-8") }
        .orEmpty()
}
