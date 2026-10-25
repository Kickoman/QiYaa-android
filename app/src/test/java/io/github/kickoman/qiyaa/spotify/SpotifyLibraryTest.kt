package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.CatalogMessages
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.ExtensionKind
import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.Metadata
import io.github.kickoman.qiyaa.spotify.protocol.MetadataEntity
import io.github.kickoman.qiyaa.spotify.protocol.ProtoReader
import io.github.kickoman.qiyaa.spotify.protocol.ProtoWriter
import io.github.kickoman.qiyaa.support.SpotifyFixtures
import io.github.kickoman.qiyaa.support.SpotifyStack
import io.github.kickoman.qiyaa.support.bytes
import io.github.kickoman.qiyaa.support.expectFailure
import io.github.kickoman.qiyaa.support.fieldOf
import io.github.kickoman.qiyaa.support.varintOf
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyLibraryTest {
    private val stacks = mutableListOf<SpotifyStack>()
    private val asked = CopyOnWriteArrayList<Pair<ExtensionKind, List<String>>>()

    @After
    fun tearDown() = stacks.forEach(SpotifyStack::close)

    @Test
    fun `SPOT-07 Liked Songs are the collection's tracks, every page, in Spotify's order`() = runBlocking {
        val (stack, library) = online()
        serveCollection(
            stack,
            "collection" to "" to page("liked-page-1"),
            "collection" to TOKEN to page("liked-page-2"),
        )
        val tracks = library.likedTracks()
        assertEquals(listOf("Plays", "Relinked", "Gone", "Lossless only"), tracks.map(Track::title))
        assertEquals(listOf(true, true, false, false), tracks.map(Track::available))
        assertEquals(listOf(TRACK_V4 to listOf(PLAYS, RELINKED, GONE, LOSSLESS)), asked)
        val first = tracks.first()
        assertEquals(PLAYS, SpotifyTracks.uriOf(first))
        assertEquals(TrackSource.SPOTIFY, first.source)
        assertEquals(listOf("Band", "Guest"), first.artists)
        assertEquals(200_000L, first.durationMs)
        assertEquals("https://i.scdn.co/image/${"40".repeat(20)}", first.coverUrl)
        val pages = stack.requests(SpotifyLibrary.COLLECTION_PATH)
        assertEquals(listOf("", TOKEN), pages.map { fieldOf(it.bytes(), 3).toString(Charsets.UTF_8) })
        for (request in pages) {
            assertEquals("owner", fieldOf(request.bytes(), 1).toString(Charsets.UTF_8))
            assertEquals("collection", fieldOf(request.bytes(), 2).toString(Charsets.UTF_8))
            assertEquals(300L, varintOf(request.bytes(), 4))
            assertEquals(CatalogMessages.COLLECTION_CONTENT_TYPE, request.getHeader("Content-Type"))
            assertEquals(CatalogMessages.COLLECTION_CONTENT_TYPE, request.getHeader("Accept"))
            assertNull(request.requestUrl!!.query)
        }
        assertEquals("spotify:user:owner:collection", library.likedSongsUri)
    }

    @Test
    fun `SPOT-15 tracks are matched by URI and one without Ogg Vorbis stays in its list, unavailable`() =
        runBlocking {
            val (_, library) = online()
            val uris = listOf(PLAYS, RELINKED, GONE, LOSSLESS, MIDDLE, RESTRICTED, MISSING, EMPTY, WIDTH)
            val tracks = library.tracks(uris)
            assertEquals(
                listOf(
                    "Plays",
                    "Relinked",
                    "Gone",
                    "Lossless only",
                    "Middle",
                    "Restricted elsewhere",
                    WIDTH_NAME,
                ),
                tracks.map(Track::title),
            )
            assertEquals(listOf(true, true, false, false, true, true, true), tracks.map(Track::available))
        }

    @Test
    fun `metadata is asked 100 URIs at a time, each once, and duplicates play in the order asked`() =
        runBlocking {
            val (stack, library) = online()
            val uris = (1..150).map(::syntheticUri)
            val tracks = library.tracks(uris + uris.take(2).reversed())
            assertEquals(
                (uris + uris.take(2).reversed()).map {
                    Ids.TRACK_URI_PREFIX + it.title()
                },
                tracks.map(SpotifyTracks::uriOf),
            )
            assertEquals(listOf(100, 50), asked.map { it.second.size }.sortedDescending())
            assertEquals(uris.toSet(), asked.flatMap { it.second }.toSet())
            assertEquals(150, asked.sumOf { it.second.size })
            val request = stack.requests(SpotifyLibrary.METADATA_PATH).first()
            assertEquals(SpotifyWeb.PROTOBUF, request.getHeader("Content-Type"))
            assertEquals("0", request.requestUrl!!.queryParameter("product"))
            assertEquals(listOf<Track>(), library.tracks(emptyList()))
            assertEquals(2, stack.requests(SpotifyLibrary.METADATA_PATH).size)
        }

    @Test
    fun `SPOT-08 playlists are the rootlist's own, folders flattened, without other statuses`() =
        runBlocking {
            val (stack, library) = online()
            stack.route("GET", ROOTLIST_PATH) {
                SpotifyFixtures.fixture("playlist-user-rootlist", "folders").response()
            }
            assertEquals(
                listOf(
                    SpotifyItem("spotify:playlist:2trxwEW4EN0zOwnWz0QoNj", "Road"),
                    SpotifyItem("spotify:playlist:2vkZuLz3WTJKcSdwuQU6Jk", "Home"),
                    SpotifyItem("spotify:playlist:2z7TqYP2x7c4ZzSGmx1wBm", "Status 200"),
                ),
                library.playlists(),
            )
            val url = stack.requests(ROOTLIST_PATH).single().requestUrl!!
            assertEquals(
                "revision,attributes,length,owner,capabilities,status_code",
                url.queryParameter("decorate"),
            )
            assertEquals("0", url.queryParameter("from"))
            assertEquals("1000", url.queryParameter("length"))
            assertEquals("BY", url.queryParameter("country"))
        }

    @Test
    fun `SPOT-08 a playlist is read 1000 entries a page by the offset asked, its tracks only`() =
        runBlocking {
            for (second in listOf("page-2", "page-2-without-position")) {
                asked.clear()
                val (stack, library) = online()
                stack.route("GET", "/playlist/v2/playlist/$PLAYLIST_ID") { request ->
                    val case = if (request.requestUrl!!.queryParameter("from") == "0") "page-1" else second
                    SpotifyFixtures.fixture("playlist", case).response()
                }
                val tracks = library.playlistTracks("spotify:playlist:$PLAYLIST_ID")
                assertEquals(
                    listOf("Plays", "Relinked", "Gone", "Lossless only", "Middle"),
                    tracks.map(Track::title),
                )
                val urls = stack.requests("/playlist/v2/playlist/$PLAYLIST_ID").map { it.requestUrl!! }
                assertEquals(listOf("0", "1000"), urls.map { it.queryParameter("from") })
                assertEquals(setOf("1000"), urls.map { it.queryParameter("length") }.toSet())
                assertEquals(
                    setOf("revision,attributes,length,owner"),
                    urls.map {
                        it.queryParameter("decorate")
                    }.toSet(),
                )
                assertEquals(
                    listOf(listOf(PLAYS, RELINKED), listOf(GONE, LOSSLESS, MIDDLE)).flatten(),
                    asked.single().second,
                )
            }
        }

    @Test
    fun `the rootlist and a next page are asked and logged without the username`() = runBlocking {
        val (stack, library) = online(
            Credentials("o wner/1", AuthenticationType.STORED_CREDENTIALS, Bytes.of(byteArrayOf(1))),
        )
        stack.route("GET", "/playlist/v2/user/o%20wner%2F1/rootlist") { MockResponse().setResponseCode(503) }
        val rootlist = expectFailure<SpotifyHttpException> { library.playlists() }
        assertEquals("HTTP 503 on GET /playlist/v2/user/{username}/rootlist", rootlist.message)
        stack.route("GET", "/radio-apollo/v5/tracks/spotify:station:user:owner:collection") {
            MockResponse().setResponseCode(503)
        }
        val page = expectFailure<SpotifyHttpException> {
            library.nextPage("hm://radio-apollo/v5/tracks/spotify:station:user:owner:collection?salt=1")
        }
        assertEquals("HTTP 503 on GET /radio-apollo/v5/tracks/{page}", page.message)
        assertEquals(1, stack.requests("/playlist/v2/user/o%20wner%2F1/rootlist").size)
        assertTrue(stack.logged.joinToString("\n"), stack.logged.none { "wner" in it })
    }

    @Test
    fun `SPOT-09 saved albums and followed artists are named by their metadata, the nameless left out`() =
        runBlocking {
            val (stack, library) = online()
            val albums =
                listOf(DOUBLE, "spotify:album:3vQSuijGAtMhauFEhySuBR", OUT_OF_ORDER, "spotify:album:x")
            serveCollection(
                stack,
                "collection" to "" to collectionPage(listOf(PLAYS) + albums),
                "artist" to "" to page("artists"),
            )
            assertEquals(
                listOf(SpotifyItem(DOUBLE, "Double"), SpotifyItem(OUT_OF_ORDER, "Discs out of order")),
                library.savedAlbums(),
            )
            assertEquals(ALBUM_V4 to albums, asked.last())
            assertEquals(
                listOf(SpotifyItem(BAND, "Band"), SpotifyItem(WORLDWIDE, "Worldwide")),
                library.followedArtists(),
            )
            assertEquals(ARTIST_V4 to listOf(BAND, WORLDWIDE), asked.last())
        }

    @Test
    fun `SPOT-09 an album plays disc by disc, and one without metadata plays nothing`() = runBlocking {
        val (_, library) = online()
        assertEquals(listOf("Gone", "Lossless only", "Middle"), library.albumTracks(DOUBLE).map(Track::title))
        assertEquals(listOf(ALBUM_V4 to listOf(DOUBLE), TRACK_V4 to listOf(GONE, LOSSLESS, MIDDLE)), asked)
        asked.clear()
        assertEquals(listOf<Track>(), library.albumTracks("spotify:album:3vQSuijGAtMhauFEhySuBR"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `SPOT-09 an artist plays at most 10 top tracks of the account's country, else worldwide`() =
        runBlocking {
            val (stack, library) = online()
            assertEquals("BY", stack.session.country)
            assertEquals(listOf<Track>(), library.artistTopTracks(BAND))
            assertEquals(TRACK_V4 to listOf(MISSING), asked.last())
            val worldwide = library.artistTopTracks(WORLDWIDE)
            assertEquals(10, worldwide.size)
            assertEquals("4qGpzvbllJDiX3D2gKFKGl", worldwide.first().id)
            assertEquals("4HJtisQeYIbOue7fFjd86u", worldwide.last().id)
            assertEquals(listOf("5a9eQ4h4ml7knvAWEU6L8J"), library.artistTopTracks(NO_COUNTRY).map(Track::id))
            val (_, polish) = online(country = "PL")
            assertEquals(listOf("Restricted elsewhere"), polish.artistTopTracks(BAND).map(Track::title))
        }

    @Test
    fun `SPOT-10 For you is the Made For You playlists of Pathfinder's home in the device's time zone`() =
        runBlocking {
            val (stack, library) = online()
            stack.route("POST", SpotifyWeb.PATHFINDER_PATH) {
                SpotifyFixtures.fixture("pathfinder-home", "made-for-you").response()
            }
            assertEquals(
                listOf(
                    SpotifyItem("spotify:playlist:2XKL2mZTkwFg69HdurNJLz", "Mix 1"),
                    SpotifyItem("spotify:playlist:31xEYAfRUK7ASRmnm7V9DB", "Weekly"),
                ),
                library.forYou(),
            )
            val request = stack.requests(SpotifyWeb.PATHFINDER_PATH).single()
            val query = queryOf(request)
            assertEquals("home", query.text("operationName"))
            assertEquals(SpotifyLibrary.HOME_HASHES.single(), hashOf(query))
            val variables = query.getValue("variables").jsonObject
            assertEquals("Europe/Minsk", variables.text("timeZone"))
            assertEquals(20, variables.getValue("sectionItemsLimit").jsonPrimitive.int)
            assertEquals("INTEGRATION_DESKTOP", variables.text("homeEndUserIntegration"))
            assertNull(request.requestUrl!!.query)
        }

    @Test
    fun `SPOT-11 search sends the trimmed text and picks the best artist, album or the found tracks`() =
        runBlocking {
            val (stack, library) = online(country = "PL")
            var case = "best-artist"
            stack.route("POST", SpotifyWeb.PATHFINDER_PATH) {
                SpotifyFixtures.fixture("pathfinder-searchDesktop", case).response()
            }
            val artist = library.searchPick("  band \n")
            assertEquals(SpotifyItem(BAND, "Band"), artist.best)
            assertEquals(BAND, artist.contextUri)
            assertEquals(listOf("Restricted elsewhere"), artist.tracks.map(Track::title))
            val query = queryOf(stack.requests(SpotifyWeb.PATHFINDER_PATH).last())
            assertEquals("searchDesktop", query.text("operationName"))
            assertEquals("band", query.getValue("variables").jsonObject.text("searchTerm"))
            assertEquals(50, query.getValue("variables").jsonObject.getValue("limit").jsonPrimitive.int)
            case = "best-album"
            val album = library.searchPick("double")
            assertEquals(SpotifyItem(DOUBLE, "Double"), album.best)
            assertEquals(listOf("Gone", "Lossless only", "Middle"), album.tracks.map(Track::title))
            case = "tracks-only"
            val found = library.searchPick("found")
            assertNull(found.best)
            assertEquals(RELINKED, found.contextUri)
            assertEquals(listOf("Relinked", "Middle"), found.tracks.map(Track::title))
        }

    @Test
    fun `SPOT-11 nothing found is no tracks, and an empty text sends nothing`() = runBlocking {
        val (stack, library) = online()
        stack.route("POST", SpotifyWeb.PATHFINDER_PATH) {
            SpotifyFixtures.fixture("pathfinder-searchDesktop", "nothing-found").response()
        }
        assertEquals(
            SpotifyPick(best = null, contextUri = "", tracks = emptyList()),
            library.searchPick("zzz"),
        )
        assertEquals(SpotifyFound.Tracks(emptyList()), library.search(" \t "))
        assertEquals(1, stack.requests(SpotifyWeb.PATHFINDER_PATH).size)
        assertEquals(0, stack.requests(SpotifyLibrary.METADATA_PATH).size)
    }

    @Test
    fun `SPOT-11 a stale hash asks the next one, newest first, and all of them stale is an outdated query`() =
        runBlocking {
            val (stack, library) = online()
            val replies = mutableListOf("412-outdated", "no-data", "best-album")
            stack.route("POST", SpotifyWeb.PATHFINDER_PATH) {
                SpotifyFixtures.fixture(
                    "pathfinder-searchDesktop",
                    replies.removeFirstOrNull() ?: "best-album",
                ).response()
            }
            assertTrue(library.search("double") is SpotifyFound.Album)
            assertEquals(
                SpotifyLibrary.SEARCH_HASHES.take(3),
                stack.requests(SpotifyWeb.PATHFINDER_PATH).map {
                    hashOf(queryOf(it))
                },
            )
            assertEquals(listOf<SpotifyProblem>(SpotifyProblem.Http(412)), stack.awaitProblems(1))
            stack.route("POST", SpotifyWeb.PATHFINDER_PATH) { MockResponse().setResponseCode(405) }
            val outdated = expectFailure<SpotifyHttpException> { library.search("double") }
            assertEquals(405, outdated.status)
            assertEquals(7, stack.requests(SpotifyWeb.PATHFINDER_PATH).size)
            val problems = stack.awaitProblems(6)
            assertEquals(
                List(4) {
                    SpotifyProblem.Http(405)
                } + SpotifyProblem.QueryOutdated,
                problems.drop(1),
            )
            stack.route("POST", SpotifyWeb.PATHFINDER_PATH) { SpotifyStack.json(JsonObject(emptyMap())) }
            val unreadable = expectFailure<QueryOutdatedException> { library.forYou() }
            assertEquals("home", unreadable.operation)
            assertEquals(SpotifyProblem.QueryOutdated, stack.awaitProblems(7).last())
        }

    @Test
    fun `SPOT-11 a network failure, a server error or a token failure does not move to the next hash`() =
        runBlocking {
            val (stack, library) = online()
            stack.route("POST", SpotifyWeb.PATHFINDER_PATH) {
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
            expectFailure<SpotifyNetworkException> { library.search("band") }
            stack.route("POST", SpotifyWeb.PATHFINDER_PATH) {
                SpotifyFixtures.fixture("pathfinder-searchDesktop", "500-with-data").response()
            }
            assertEquals(500, expectFailure<SpotifyHttpException> { library.search("band") }.status)
            val hashes = stack.requests(SpotifyWeb.PATHFINDER_PATH).map { hashOf(queryOf(it)) }
            assertEquals(setOf(SpotifyLibrary.SEARCH_HASHES.first()), hashes.toSet())
            val (other, otherLibrary) = online()
            other.route("POST", "/v1/clienttoken") { MockResponse().setResponseCode(400) }
            other.clock.addAndGet(30L * 24 * 3600 * 1000)
            val token = expectFailure<SpotifyHttpException> { otherLibrary.search("band") }
            assertEquals(400, token.status)
            assertEquals(SpotifyWeb.CLIENT_TOKEN_PATH, token.path)
            assertEquals(0, other.requests(SpotifyWeb.PATHFINDER_PATH).size)
        }

    @Test
    fun `SPOT-12 a station is the track's context-resolve, then the page of its next_page_url`() =
        runBlocking {
            val (stack, library) = online()
            val station = SpotifyLibrary.STATION_PATH + "01TrY6CZi6JaolPzVQ3HW1"
            stack.route("GET", station) { SpotifyFixtures.fixture("context-resolve", "station").response() }
            val first = library.station(PLAYS)
            assertEquals(
                listOf("0DMtmfcL8hiwfotaADfSEl", "0FFVklPKqo1GDKiKwtjAAm"),
                first.tracks.map(Track::id),
            )
            val nextPath = "/radio-apollo/v5/tracks/spotify:station:track:01TrY6CZi6JaolPzVQ3HW1"
            assertEquals("hm:/$nextPath?salt=2&prev_tracks=21,22", first.next)
            val request = stack.requests(station).single()
            assertEquals(SpotifyWeb.JSON, request.getHeader("Accept"))
            assertNull(request.requestUrl!!.query)
            stack.route("GET", nextPath) {
                SpotifyFixtures.fixture("radio-apollo-tracks", "last-page").response()
            }
            val last = library.nextPage(first.next)
            assertEquals(ContextPage(listOf(syntheticTrack("0oCRBoaQNptb6zOx7UMdad")), ""), last)
            assertEquals("salt=2&prev_tracks=21,22", stack.requests(nextPath).single().requestUrl!!.query)
            val elsewhere =
                expectFailure<SpotifyContentException> {
                    library.nextPage("https://spclient.wg.spotify.com/next")
                }
            assertEquals("a next page that does not start with hm://", elsewhere.message)
            assertEquals(0, stack.requests("/next").size)
        }

    @Test
    fun `SPOT-20 autoplay posts the context and the recent tracks, its next page is next_page_url`() =
        runBlocking {
            val (stack, library) = online()
            stack.route("POST", SpotifyLibrary.AUTOPLAY_PATH) {
                SpotifyFixtures.fixture("context-resolve-autoplay", "liked-songs").response()
            }
            val page = library.autoplay(library.likedSongsUri, listOf(PLAYS, RELINKED))
            assertEquals(
                listOf("0kPXFaUSdc0QjS9ngeENib", "0mJpDhxRviK0IdYXc4Ivec"),
                page.tracks.map(Track::id),
            )
            val request = stack.requests(SpotifyLibrary.AUTOPLAY_PATH).single()
            assertTrue(
                request.bytes().contentEquals(
                    CatalogMessages.autoplayRequest("spotify:user:owner:collection", listOf(PLAYS, RELINKED)),
                ),
            )
            assertEquals(SpotifyWeb.PROTOBUF, request.getHeader("Content-Type"))
            assertEquals(SpotifyWeb.JSON, request.getHeader("Accept"))
            assertNull(request.requestUrl!!.query)
            stack.route("GET", "/radio-apollo/v5/tracks/spotify:station:x") {
                SpotifyFixtures.fixture("radio-apollo-tracks", "last-page").response()
            }
            assertEquals(listOf("0oCRBoaQNptb6zOx7UMdad"), library.nextPage(page.next).tracks.map(Track::id))
            val next = stack.requests("/radio-apollo/v5/tracks/spotify:station:x").single().requestUrl!!
            assertEquals("salt=1&prev_tracks=a,b", next.query)
            stack.route("POST", SpotifyLibrary.AUTOPLAY_PATH) { MockResponse().setResponseCode(500) }
            assertEquals(
                500,
                expectFailure<SpotifyHttpException> {
                    library.autoplay(BAND, emptyList())
                }.status,
            )
        }

    @Test
    fun `a reply that cannot be read as a whole fails the call and is reported as content`() = runBlocking {
        val (stack, library) = online()
        stack.route("POST", SpotifyLibrary.AUTOPLAY_PATH) {
            SpotifyFixtures.fixture("context-resolve-autoplay", "loading").response()
        }
        val loading = expectFailure<SpotifyContentException> { library.autoplay(BAND, emptyList()) }
        assertEquals("POST /context-resolve/v1/autoplay: a context that cannot be read", loading.message)
        stack.route("POST", SpotifyLibrary.METADATA_PATH) {
            SpotifyFixtures.fixture("extended-metadata", "tracks-broken-entity").response()
        }
        expectFailure<SpotifyContentException> { library.tracks(listOf(PLAYS)) }
        serveCollection(stack, "collection" to "" to BROKEN)
        expectFailure<SpotifyContentException> { library.likedTracks() }
        stack.route("GET", ROOTLIST_PATH) { SpotifyStack.protobuf(BROKEN) }
        expectFailure<SpotifyContentException> { library.playlists() }
        assertEquals(List(4) { SpotifyProblem.Content }, stack.awaitProblems(4))
        assertTrue(
            stack.logged.any {
                it ==
                    "POST /collection/v2/paging: a collection page that does not parse"
            },
        )
    }

    private suspend fun online(
        credentials: Credentials = SpotifyStack.STORED,
        country: String = "BY",
    ): Pair<SpotifyStack, SpotifyLibrary> {
        val stack = SpotifyStack().also { stacks += it }
        stack.accessPoint.country = country
        serveMetadata(stack)
        stack.loginStored(credentials)
        return stack to SpotifyLibrary(stack.web, stack.session, timeZone = { "Europe/Minsk" })
    }

    private fun serveMetadata(stack: SpotifyStack) {
        val known =
            mapOf(
                TRACK_V4 to entitiesOf("tracks"),
                ALBUM_V4 to entitiesOf("albums"),
                ARTIST_V4 to entitiesOf("artists"),
            )
        stack.route("POST", SpotifyLibrary.METADATA_PATH) { request ->
            val (kind, uris) = askedIn(request)
            asked += kind to uris
            val array = ProtoWriter()
            for (uri in uris.reversed()) {
                val entity =
                    known.getValue(kind)[uri]
                        ?: if (kind == TRACK_V4) {
                            MetadataEntity(uri, 200, Bytes.of(syntheticPayload(uri)))
                        } else {
                            MetadataEntity(uri, 404, Bytes.EMPTY)
                        }
                val header = ProtoWriter().varint(1, entity.status.toLong())
                val data = ProtoWriter().message(1, header).string(2, entity.uri)
                if (!entity.payload.isEmpty()) {
                    data.message(3, ProtoWriter().string(1, "type.googleapis.com/x").bytes(2, entity.payload))
                }
                array.message(3, data)
            }
            SpotifyStack.protobuf(ProtoWriter().message(2, array).toByteArray())
        }
    }

    private fun serveCollection(stack: SpotifyStack, vararg pages: Pair<Pair<String, String>, ByteArray>) {
        val bySetAndToken = pages.toMap()
        stack.route("POST", SpotifyLibrary.COLLECTION_PATH) { request ->
            val set = fieldOf(request.bytes(), 2).toString(Charsets.UTF_8)
            val token = fieldOf(request.bytes(), 3).toString(Charsets.UTF_8)
            val body = bySetAndToken[set to token] ?: return@route MockResponse().setResponseCode(404)
            SpotifyStack.protobuf(body).setHeader("Content-Type", CatalogMessages.COLLECTION_CONTENT_TYPE)
        }
    }

    private fun askedIn(request: RecordedRequest): Pair<ExtensionKind, List<String>> {
        var kind: ExtensionKind? = null
        val uris = ArrayList<String>()
        val reader = ProtoReader(request.bytes())
        while (reader.next()) {
            if (reader.field != 2) continue
            val entity = reader.bytes()
            uris += fieldOf(entity, 1).toString(Charsets.UTF_8)
            val code = varintOf(fieldOf(entity, 2), 1)
            kind = ExtensionKind.entries.single { it.code.toLong() == code }
        }
        return kind!! to uris
    }

    private fun entitiesOf(case: String): Map<String, MetadataEntity> =
        Metadata.parseReply(SpotifyFixtures.fixture("extended-metadata", case).body)!!.associateBy { it.uri }

    private fun page(case: String): ByteArray = SpotifyFixtures.fixture("collection-paging", case).body

    private fun collectionPage(uris: List<String>): ByteArray {
        val page = ProtoWriter()
        for (uri in uris) page.message(1, ProtoWriter().string(1, uri).varint(2, 1_729_000_000))
        return page.toByteArray()
    }

    private fun syntheticUri(mark: Int): String {
        val gid = ByteArray(Ids.GID_SIZE) { if (it < 14) 0x11 else 0 }
        gid[14] = (mark ushr 8).toByte()
        gid[15] = mark.toByte()
        return Ids.TRACK_URI_PREFIX + Ids.base62FromGid(Bytes.of(gid))
    }

    private fun String.title(): String = removePrefix(Ids.TRACK_URI_PREFIX)

    private fun syntheticPayload(uri: String): ByteArray {
        val id = SpotifyLibrary.idOf(uri)
        return ProtoWriter()
            .bytes(1, Ids.gidFromBase62(id)!!)
            .string(2, id)
            .message(12, ProtoWriter().bytes(1, ByteArray(Ids.FILE_ID_SIZE) { 7 }).varint(2, 2))
            .toByteArray()
    }

    private fun syntheticTrack(id: String): Track =
        Track(id = id, title = id, available = true, source = TrackSource.SPOTIFY)

    private fun queryOf(request: RecordedRequest): JsonObject =
        Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject

    private fun hashOf(query: JsonObject): String =
        query.getValue("extensions").jsonObject.getValue("persistedQuery").jsonObject.text("sha256Hash")

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        val TRACK_V4 = ExtensionKind.TRACK_V4
        val ALBUM_V4 = ExtensionKind.ALBUM_V4
        val ARTIST_V4 = ExtensionKind.ARTIST_V4
        const val TOKEN = "synthetic-page-2"
        const val PLAYS = "spotify:track:01TrY6CZi6JaolPzVQ3HW1"
        const val RELINKED = "spotify:track:03MTWdfYAdskMHF9RG7pS2"
        const val GONE = "spotify:track:05GlUjSXSkbvb3uJNwb7O3"
        const val LOSSLESS = "spotify:track:07zNSqvXaqUFzpkjJmePK4"
        const val MIDDLE = "spotify:track:09tfQx8WsxDPXL9TFcixG5"
        const val RESTRICTED = "spotify:track:0bmHODLVKEn0m6ZtB2mfC6"
        const val MISSING = "spotify:track:0dg9MKoV2L6aKsP3wSpXy7"
        const val EMPTY = "spotify:track:0f9BKR1UkRPl8OEDsItFu8"
        const val WIDTH = "spotify:track:0h33IXETCYyvxaudoyxnq9"
        const val WIDTH_NAME = "Width before size class"
        const val DOUBLE = "spotify:album:3s3Yy53I0gjWnN0upSL4JP"
        const val OUT_OF_ORDER = "spotify:album:3xKksoWFSAvryQvedoWcxS"
        const val BAND = "spotify:artist:3Wnc3N7wFZYCFqjLljIpI5"
        const val WORLDWIDE = "spotify:artist:3YgE1TKvY6HN3M9lh9M7E6"
        const val NO_COUNTRY = "spotify:artist:40a600nvgdqXs7YVcZPPA7"
        const val PLAYLIST_ID = "2trxwEW4EN0zOwnWz0QoNj"
        const val ROOTLIST_PATH = "/playlist/v2/user/owner/rootlist"
        val BROKEN = byteArrayOf(0x0a, 0x05, 0x68)
    }
}
