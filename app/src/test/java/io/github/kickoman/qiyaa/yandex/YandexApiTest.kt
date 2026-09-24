package io.github.kickoman.qiyaa.yandex

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
import java.net.URLDecoder

/** Port of tests/test_library.cpp: every endpoint against a mock server. */
class YandexApiTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, MockResponse>()
    private val requests = HashMap<String, RecordedRequest>()
    private val bodies = HashMap<String, String>()
    private lateinit var api: YandexApi
    private lateinit var lib: Library

    /** Wraps a body in the {invocationInfo, result} envelope. */
    private fun result(method: String, path: String, body: String) = json(method, path, """{"invocationInfo":{},"result":$body}""")
    private fun json(method: String, path: String, body: String, code: Int = 200) {
        routes["$method $path"] = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun trackJson(id: Int, title: String, album: Int = 0): String {
        var s = """{"id":$id,"title":"$title","artists":[{"name":"Artist"}],"durationMs":180000"""
        if (album != 0) s += ""","albums":[{"id":$album}]"""
        return "$s}"
    }

    private fun last(path: String) = requests[path] ?: error("no request to $path")
    private fun RecordedRequest.bodyText(): String = bodies[requestUrl!!.encodedPath].orEmpty()
    private fun RecordedRequest.formValue(key: String): String =
        bodyText().split('&').map { it.split('=', limit = 2) }.firstOrNull { URLDecoder.decode(it[0], "UTF-8") == key }?.let { URLDecoder.decode(it[1], "UTF-8") }.orEmpty()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                requests[path] = request
                bodies[path] = request.body.clone().readUtf8()
                return routes["${request.method} $path"] ?: MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
            }
        }
        server.start()
        api = YandexApi(OkHttpClient(), server.url("/").toString().removeSuffix("/"))
        api.token = "test-token"
        lib = Library(api)
        result("GET", "/account/status", """{"account":{"uid":42,"login":"kick","displayName":"Kick"}}""")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun connectsAccountWithAuthHeader() = runBlocking {
        val acc = lib.connectAccount()
        assertEquals("42", acc.uid)
        assertTrue(lib.isLoggedIn)
        assertEquals("OAuth test-token", last("/account/status").getHeader("Authorization"))
        assertEquals("ru", last("/account/status").getHeader("Accept-Language"))
    }

    @Test
    fun likedTracksFetchesMetadataAndRemembersLikes() = runBlocking {
        lib.connectAccount()
        result("GET", "/users/42/likes/tracks", """{"library":{"uid":42,"tracks":[{"id":"1","albumId":"10"},{"id":"2","albumId":"20"}]}}""")
        result("POST", "/tracks/", "[" + trackJson(1, "One", 10) + "," + trackJson(2, "Two", 20) + "]")
        val tracks = lib.likedTracks()
        assertEquals(2, tracks.size)
        assertEquals("Two", tracks[1].title)
        assertEquals("1,2", last("/tracks/").formValue("track-ids"))
        assertEquals("false", last("/tracks/").formValue("with-positions"))
        assertTrue(lib.isLiked("1"))
        assertFalse(lib.isLiked("3"))
    }

    @Test
    fun playlistsAndTheirTracks() = runBlocking {
        lib.connectAccount()
        result("GET", "/users/42/playlists/list", """[{"uid":42,"kind":1003,"title":"Дорога","trackCount":2}]""")
        val lists = lib.userPlaylists()
        assertEquals(1, lists.size)
        assertEquals("1003", lists[0].kind)
        assertEquals("Дорога", lists[0].title)
        assertEquals(2, lists[0].trackCount)
        // Embedded track objects are used directly.
        result("GET", "/users/42/playlists/1003", """{"tracks":[{"id":5,"track":${trackJson(5, "Five")}}]}""")
        val tracks = lib.playlistTracks(lists[0])
        assertEquals(listOf("Five"), tracks.map { it.title })
    }

    @Test
    fun playlistWithoutEmbeddedTracksFetchesByIds() = runBlocking {
        result("GET", "/users/7/playlists/3", """{"tracks":[{"id":1},{"id":2}]}""")
        result("POST", "/tracks/", "[" + trackJson(1, "One") + "," + trackJson(2, "Two") + "]")
        val tracks = lib.playlistTracks(PlaylistRef("7", "3", "x", 2))
        assertEquals(2, tracks.size)
        assertEquals("1,2", last("/tracks/").formValue("track-ids"))
    }

    @Test
    fun artistsAndTopTracks() = runBlocking {
        lib.connectAccount()
        result("GET", "/users/42/likes/artists", """[{"id":9,"name":"Кино"},{"artist":{"id":10,"name":"Земфира"}}]""")
        val artists = lib.likedArtists()
        assertEquals(listOf("9", "10"), artists.map { it.id })
        result("GET", "/artists/9/track-ids-by-rating", """{"artist":{},"tracks":["1","2"]}""")
        result("POST", "/tracks/", "[" + trackJson(1, "One") + "," + trackJson(2, "Two") + "]")
        assertEquals(2, lib.artistTopTracks("9").size)
    }

    @Test
    fun albumsSkipPodcastsAndFlattenVolumes() = runBlocking {
        lib.connectAccount()
        result("GET", "/users/42/likes/albums", """[{"id":100},{"album":{"id":200}}]""")
        result("POST", "/albums", """[{"id":100,"title":"Группа крови","artists":[{"name":"Кино"}]},{"id":200,"title":"Cast","type":"podcast"}]""")
        val albums = lib.likedAlbums()
        assertEquals(listOf(NamedRef("100", "Кино - Группа крови")), albums)
        assertEquals("100,200", last("/albums").formValue("album-ids"))
        result("GET", "/albums/100/with-tracks", """{"volumes":[[${trackJson(1, "A")}],[${trackJson(2, "B")}]]}""")
        val tracks = lib.albumTracks("100")
        assertEquals(2, tracks.size)
        assertEquals("100", tracks[0].albumId)
    }

    @Test
    fun stations() = runBlocking {
        result("GET", "/rotor/stations/list", """[{"station":{"id":{"type":"genre","tag":"rock"},"name":"Рок"}}]""")
        val s = lib.stations()
        assertEquals(listOf(Station("genre:rock", "genre", "Рок")), s)
        assertEquals("ru", last("/rotor/stations/list").requestUrl!!.queryParameter("language"))
    }

    @Test
    fun waveSession() = runBlocking {
        result("POST", "/rotor/session/new", """{"radioSessionId":"S1","batchId":"B1","sequence":[{"type":"track","track":${trackJson(1, "W1")}}]}""")
        val first = lib.startWave(listOf("user:onyourwave"))
        assertEquals("S1", first.sessionId)
        assertEquals(1, first.tracks.size)
        val body = Json.parseToJsonElement(last("/rotor/session/new").bodyText()) as JsonObject
        assertEquals("user:onyourwave", body["seeds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("true", body["includeTracksInResponse"]!!.jsonPrimitive.content)
        assertEquals("application/json", last("/rotor/session/new").getHeader("Content-Type")?.substringBefore(';'))

        result("POST", "/rotor/session/S1/tracks", """{"batchId":"B2","sequence":[{"track":${trackJson(2, "W2")}}]}""")
        val more = lib.moreWave("S1", listOf("1"))
        assertEquals("W2", more.tracks[0].title)
        assertEquals("S1", more.sessionId)
        val moreBody = Json.parseToJsonElement(last("/rotor/session/S1/tracks").bodyText()) as JsonObject
        assertEquals("1", moreBody["queue"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun searchBestArtist() = runBlocking {
        result("GET", "/search", """{"best":{"type":"artist","result":{"id":9,"name":"Кино"}},"tracks":{"results":[${trackJson(3, "Кукушка")}]}}""")
        val r = lib.search("кино")
        assertEquals("artist", r.bestType)
        assertEquals("9", r.bestId)
        assertEquals("Кино", r.bestName)
        assertEquals(1, r.tracks.size)
        val url = last("/search").requestUrl!!
        assertEquals("кино", url.queryParameter("text"))
        assertEquals("all", url.queryParameter("type"))
        assertEquals("0", url.queryParameter("page"))
    }

    @Test
    fun likeUnlikeDislike() = runBlocking {
        lib.connectAccount()
        result("POST", "/users/42/likes/tracks/add-multiple", """{"revision":1}""")
        result("POST", "/users/42/likes/tracks/remove", """{"revision":2}""")
        result("POST", "/users/42/dislikes/tracks/add-multiple", """{"revision":3}""")
        lib.setLiked("77", true)
        assertTrue(lib.isLiked("77"))
        assertEquals("77", last("/users/42/likes/tracks/add-multiple").formValue("track-ids"))
        lib.setLiked("77", false)
        assertFalse(lib.isLiked("77"))
        lib.dislike("1")
        assertFalse(lib.isLiked("1"))
    }

    @Test
    fun errorsAreReported() = runBlocking {
        lib.connectAccount()
        json("GET", "/users/42/likes/artists", """{"error":{"name":"session-expired","message":"Token expired"}}""", 401)
        try {
            lib.likedArtists()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("HTTP 401: Token expired", e.message)
        }
        json("GET", "/users/42/likes/albums", """{"nope":1}""")
        try {
            lib.likedAlbums()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("unexpected response", e.message)
        }
    }

    @Test
    fun resolvesTrackUrlInTwoHops() = runBlocking {
        val infoUrl = server.url("/download-info/xyz").toString()
        result("GET", "/tracks/1/download-info", """[{"codec":"mp3","bitrateInKbps":192,"preview":false,"downloadInfoUrl":"$infoUrl"}]""")
        json("GET", "/download-info/xyz", """{"host":"h.net","path":"/p/q","ts":"0005","s":"abc"}""")
        val r = api.resolveTrackUrl("1:99")
        assertEquals(192, r.bitrateKbps)
        assertTrue(r.url.startsWith("https://h.net/get-mp3/"))
        assertTrue(r.url.endsWith("/0005/p/q"))
        assertEquals("json", last("/download-info/xyz").requestUrl!!.queryParameter("format"))
    }

    @Test
    fun playAudioSendsTheWinampFields() = runBlocking {
        val acc = lib.connectAccount()
        result("POST", "/play-audio", "\"ok\"")
        api.reportPlayStarted(acc, Track("1", "T", listOf("A"), "10", 180000), "PLAY-ID")
        val body = last("/play-audio").bodyText()
        val form = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
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
    fun tracksAreFetchedInChunksOf250() = runBlocking {
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                val ids = request.body.readUtf8().substringAfter("track-ids=").substringBefore('&').let { URLDecoder.decode(it, "UTF-8") }.split(',')
                return MockResponse().setBody("""{"invocationInfo":{},"result":[${ids.joinToString(",") { trackJson(it.toInt(), "t$it") }}]}""")
            }
        }
        val tracks = lib.tracksByIds((1..300).map { it.toString() })
        assertEquals(300, tracks.size)
        assertEquals("t300", tracks.last().title)
        assertEquals(2, calls)
    }
}
