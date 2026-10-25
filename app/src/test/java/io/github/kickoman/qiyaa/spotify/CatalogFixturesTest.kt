package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AlbumRef
import io.github.kickoman.qiyaa.spotify.protocol.ArtistRef
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.CatalogMessages
import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.ListItem
import io.github.kickoman.qiyaa.spotify.protocol.Metadata
import io.github.kickoman.qiyaa.spotify.protocol.SpotifyAlbum
import io.github.kickoman.qiyaa.spotify.protocol.SpotifyArtist
import io.github.kickoman.qiyaa.spotify.protocol.SpotifyTrack
import io.github.kickoman.qiyaa.support.SpotifyFixture
import io.github.kickoman.qiyaa.support.SpotifyFixtures
import io.github.kickoman.qiyaa.support.SpotifyStack
import io.github.kickoman.qiyaa.track.TrackSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogFixturesTest {
    @Test
    fun `every endpoint of spec fixtures spotify has a test here`() {
        assertEquals(ENDPOINTS, SpotifyFixtures.endpoints())
    }

    @Test
    fun `extended-metadata matches entities by URI and reads tracks, albums and artists`() {
        SpotifyFixtures.forEachCase("extended-metadata") { fixture ->
            assertTrue(fixture.protobuf)
            val entities = Metadata.parseReply(fixture.body) ?: return@forEachCase UNREADABLE
            val key = fixture.case.substringBefore('-').removeSuffix("s")
            buildJsonObject {
                put(
                    "entities",
                    JsonArray(
                        entities.map { entity ->
                            buildJsonObject {
                                put("uri", entity.uri)
                                put("status", entity.status)
                                val payload = entity.payload.takeIf { entity.found }
                                put(key, payload?.let { parsedAs(key, it) } ?: JsonNull)
                            }
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun `SPOT-19 a track's Track has the expected id, availability and the cover of 400 px`() {
        val expected = SpotifyFixtures.expected("extended-metadata", "tracks")
        val entities = Metadata.parseReply(SpotifyFixtures.fixture("extended-metadata", "tracks").body)!!
        val found = entities.filter { it.found }
        assertEquals(7, found.size)
        for (entity in found) {
            val track = SpotifyTracks.trackOf(Metadata.parseTrack(entity.payload)!!)
            val want = expected.getValue("entities").jsonArray
                .map { it.jsonObject }
                .single { it.text("uri") == entity.uri }
                .getValue("track").jsonObject
            assertEquals(want.text("id"), track.id)
            assertEquals(want.text("name"), track.title)
            assertEquals(Ids.TRACK_URI_PREFIX + track.id, SpotifyTracks.uriOf(track))
            assertEquals(want.getValue("durationMs").jsonPrimitive.content.toLong(), track.durationMs)
            assertEquals(want.getValue("available").jsonPrimitive.content.toBoolean(), track.available)
            assertEquals(
                want.getValue("coverUrls").jsonObject["400"]?.jsonPrimitive?.contentOrNull,
                track.coverUrl,
            )
            assertEquals("", track.albumId)
            assertEquals(TrackSource.SPOTIFY, track.source)
        }
    }

    @Test
    fun `collection-paging drops removed items and ends at an empty token or an empty page`() {
        SpotifyFixtures.forEachCase("collection-paging") { fixture ->
            assertTrue(fixture.protobuf)
            val page = CatalogMessages.parseCollectionPage(fixture.body) ?: return@forEachCase UNREADABLE
            buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        for (item in page.items) {
                            add(
                                buildJsonObject {
                                    put("uri", item.uri)
                                    put("addedAt", item.addedAt)
                                },
                            )
                        }
                    },
                )
                put("nextPageToken", page.nextPageToken)
                put("last", page.last)
            }
        }
    }

    @Test
    fun `the rootlist flattens folders and keeps playlists of status 0 or 200`() {
        SpotifyFixtures.forEachCase("playlist-user-rootlist") { fixture ->
            listPage(fixture) { items -> put("playlists", items(CatalogMessages.playlistsOf(items))) }
        }
    }

    @Test
    fun `a playlist page pages by the offset asked and plays only its tracks`() {
        SpotifyFixtures.forEachCase("playlist") { fixture ->
            listPage(fixture) { items ->
                put(
                    "trackUris",
                    strings(
                        items.map(ListItem::uri).filter {
                            it.startsWith(Ids.TRACK_URI_PREFIX)
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun `storage-resolve is usable only as CDN links`() {
        SpotifyFixtures.forEachCase("storage-resolve-files-audio-interactive") { fixture ->
            assertTrue(fixture.protobuf)
            val storage = Metadata.parseStorageResolve(fixture.body) ?: return@forEachCase UNREADABLE
            buildJsonObject {
                put("result", storage.result.code)
                put("urls", strings(storage.urls))
                put("usable", storage.usable)
            }
        }
    }

    @Test
    fun `a station, an autoplay and a next page read the first page's tracks and its next page`() {
        for (endpoint in listOf("context-resolve", "context-resolve-autoplay", "radio-apollo-tracks")) {
            SpotifyFixtures.forEachCase(endpoint) { fixture ->
                val context = CatalogJson.context(fixture.body) ?: return@forEachCase UNREADABLE
                buildJsonObject {
                    put("trackUris", strings(context.trackUris))
                    put("next", context.next)
                }
            }
        }
    }

    @Test
    fun `apresolve drops empty entries and falls back for a new session, whatever the status`() {
        SpotifyFixtures.forEachCase("apresolve") { fixture ->
            val resolved = SpotifySession.resolvedOf(fixture.body, knownSpclients = emptyList())
            buildJsonObject {
                put("accessPoints", strings(resolved.accessPoints))
                put("spclients", strings(resolved.spclients))
            }
        }
    }

    @Test
    fun `SPOT-10 Pathfinder's home through the library, stale replies asking the next hash`() = runBlocking {
        withLibrary { stack, library, problems ->
            SpotifyFixtures.forEachCase("pathfinder-home") { fixture ->
                pathfinder(stack, problems, fixture, SpotifyLibrary.HOME_HASHES) {
                    buildJsonObject {
                        put("playlists", items(library.forYou().map { ListItem(it.uri, it.name) }))
                    }
                }
            }
        }
    }

    @Test
    fun `SPOT-11 Pathfinder's search through the library, stale replies asking the next hash`() =
        runBlocking {
            withLibrary { stack, library, problems ->
                SpotifyFixtures.forEachCase("pathfinder-searchDesktop") { fixture ->
                    pathfinder(stack, problems, fixture, SpotifyLibrary.SEARCH_HASHES) {
                        val found = library.search("band")
                        val (kind, best) =
                            when (found) {
                                is SpotifyFound.Artist -> "artist" to found.best
                                is SpotifyFound.Album -> "album" to found.best
                                is SpotifyFound.Tracks -> "tracks" to null
                            }
                        buildJsonObject {
                            put("kind", kind)
                            put("best", best?.let { item(ListItem(it.uri, it.name)) } ?: JsonNull)
                            put("trackUris", strings(found.trackUris))
                        }
                    }
                }
            }
        }

    private suspend fun withLibrary(
        check: suspend (SpotifyStack, SpotifyLibrary, MutableList<SpotifyProblem>) -> Unit,
    ) = coroutineScope {
        SpotifyStack().use { stack ->
            stack.loginStored()
            val problems = mutableListOf<SpotifyProblem>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    stack.web.problems.collect {
                        problems +=
                            it
                    }
                }
            try {
                check(stack, SpotifyLibrary(stack.web, stack.session), problems)
            } finally {
                collector.cancel()
            }
        }
    }

    private suspend fun pathfinder(
        stack: SpotifyStack,
        problems: MutableList<SpotifyProblem>,
        fixture: SpotifyFixture,
        hashes: List<String>,
        call: suspend () -> JsonObject,
    ): JsonObject {
        stack.route("POST", SpotifyWeb.PATHFINDER_PATH) { fixture.response() }
        val asked = stack.requests(SpotifyWeb.PATHFINDER_PATH).size
        problems.clear()
        val outcome =
            try {
                call()
            } catch (failed: SpotifyHttpException) {
                buildJsonObject { put("error", buildJsonObject { put("status", failed.status) }) }
            } catch (failed: SpotifyContentException) {
                UNREADABLE
            } catch (failed: QueryOutdatedException) {
                UNREADABLE
            }
        repeat(2) { yield() }
        val stale = SpotifyProblem.QueryOutdated in problems
        val sent = stack.requests(SpotifyWeb.PATHFINDER_PATH).drop(asked).map { request ->
            Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                .getValue("extensions").jsonObject
                .getValue("persistedQuery").jsonObject
                .text("sha256Hash")
        }
        assertEquals(fixture.case, if (stale) hashes else hashes.take(1), sent)
        if (outcome == UNREADABLE) assertEquals(fixture.case, !stale, SpotifyProblem.Content in problems)
        return if (stale) {
            buildJsonObject {
                put("stale", true)
                put("withNoHashLeft", outcome)
            }
        } else {
            JsonObject(mapOf("stale" to JsonPrimitive(false)) + outcome)
        }
    }

    private fun listPage(
        fixture: SpotifyFixture,
        more: JsonObjectBuilder.(List<ListItem>) -> Unit,
    ): JsonObject {
        assertTrue(fixture.protobuf)
        val page = CatalogMessages.parseListPage(fixture.body) ?: return UNREADABLE
        return buildJsonObject {
            put("requestedFrom", fixture.requestedFrom)
            put("name", page.name)
            put("length", page.length)
            put("from", page.from)
            put("truncated", page.truncated)
            put("items", items(page.items))
            put("more", page.hasMoreAfter(fixture.requestedFrom, SpotifyLibrary.LIST_PAGE))
            more(page.items)
        }
    }

    private fun parsedAs(key: String, payload: Bytes): JsonElement = when (key) {
        "track" -> Metadata.parseTrack(payload)?.let { trackJson(it) }
        "album" -> Metadata.parseAlbum(payload)?.let(::albumJson)
        "artist" -> Metadata.parseArtist(payload)?.let(::artistJson)
        else -> throw AssertionError("no parser for $key")
    } ?: JsonNull

    private fun trackJson(track: SpotifyTrack, whole: Boolean = true): JsonObject = buildJsonObject {
        put("id", Ids.base62FromGid(track.gid))
        put("name", track.name)
        put("artists", JsonArray(track.artists.map(::artistRefJson)))
        put("album", albumRefJson(track.album))
        put("durationMs", track.durationMs)
        put(
            "files",
            JsonArray(
                track.files.map {
                    buildJsonObject {
                        put("id", it.id.hex())
                        put("format", it.format.code)
                    }
                },
            ),
        )
        put("alternatives", JsonArray(track.alternatives.map { trackJson(it, whole = false) }))
        if (!whole) return@buildJsonObject
        put("available", SpotifyTracks.isAvailable(track))
        put(
            "coverUrls",
            buildJsonObject {
                for (pixels in listOf(64, 400, 1000)) {
                    put(pixels.toString(), SpotifyTracks.coverUrlOf(track.album.covers, pixels))
                }
            },
        )
        put(
            "play",
            buildJsonObject {
                for (kbps in listOf(96, 160, 320)) {
                    val file = Metadata.pickOggVorbis(track, kbps)
                    put(
                        kbps.toString(),
                        file?.let {
                            buildJsonObject {
                                put("trackId", Ids.base62FromGid(it.track))
                                put("fileId", it.file.id.hex())
                                put("format", it.file.format.code)
                            }
                        } ?: JsonNull,
                    )
                }
            },
        )
    }

    private fun albumRefJson(album: AlbumRef): JsonObject = buildJsonObject {
        put("id", Ids.base62FromGid(album.gid))
        put("name", album.name)
        put("year", album.year)
        put(
            "covers",
            JsonArray(
                album.covers.map {
                    buildJsonObject {
                        put("id", it.id.hex())
                        put("pixels", it.pixels)
                    }
                },
            ),
        )
    }

    private fun artistRefJson(artist: ArtistRef): JsonObject = buildJsonObject {
        put("id", Ids.base62FromGid(artist.gid))
        put("name", artist.name)
    }

    private fun albumJson(album: SpotifyAlbum): JsonObject = buildJsonObject {
        put("id", Ids.base62FromGid(album.gid))
        put("name", album.name)
        put("artists", JsonArray(album.artists.map(::artistRefJson)))
        put("trackIds", strings(album.tracks.map(Ids::base62FromGid)))
    }

    private fun artistJson(artist: SpotifyArtist): JsonObject = buildJsonObject {
        put("id", Ids.base62FromGid(artist.gid))
        put("name", artist.name)
        put(
            "topTracks",
            JsonArray(
                artist.topTracks.map {
                    buildJsonObject {
                        put("country", it.country)
                        put("trackIds", strings(it.tracks.map(Ids::base62FromGid)))
                    }
                },
            ),
        )
        put(
            "topTracksIn",
            buildJsonObject {
                for (country in listOf("BY", "PL", "")) {
                    put(country, strings(Metadata.topTracksIn(artist, country).map(Ids::base62FromGid)))
                }
            },
        )
    }

    private fun items(items: List<ListItem>): JsonArray = JsonArray(items.map(::item))

    private fun item(item: ListItem): JsonObject = buildJsonObject {
        put("uri", item.uri)
        put("name", item.name)
    }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        val ENDPOINTS =
            listOf(
                "apresolve",
                "collection-paging",
                "context-resolve",
                "context-resolve-autoplay",
                "extended-metadata",
                "pathfinder-home",
                "pathfinder-searchDesktop",
                "playlist",
                "playlist-user-rootlist",
                "radio-apollo-tracks",
                "storage-resolve-files-audio-interactive",
            )
        val UNREADABLE = JsonObject(mapOf("unreadable" to JsonPrimitive(true)))
    }
}
