package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.CatalogMessages
import io.github.kickoman.qiyaa.spotify.protocol.ExtensionKind
import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.ListItem
import io.github.kickoman.qiyaa.spotify.protocol.Metadata
import io.github.kickoman.qiyaa.spotify.protocol.MetadataEntity
import io.github.kickoman.qiyaa.track.Track
import java.util.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class SpotifyItem(val uri: String, val name: String)

data class SpotifyFound(
    val kind: Kind = Kind.TRACKS,
    val best: SpotifyItem? = null,
    val trackUris: List<String> = emptyList(),
    val artists: List<SpotifyItem> = emptyList(),
    val albums: List<SpotifyFoundAlbum> = emptyList(),
) {
    enum class Kind(val key: String) { ARTIST("artist"), ALBUM("album"), TRACK("track"), TRACKS("tracks") }
}

data class SpotifyFoundAlbum(
    val uri: String,
    val name: String,
    val artists: List<String> = emptyList(),
    val year: Int? = null,
    val single: Boolean = false,
)

data class SpotifyPick(val best: SpotifyItem?, val contextUri: String, val tracks: List<Track>)

data class ContextPage(val tracks: List<Track>, val next: String)

class SpotifyLibrary(
    private val web: SpotifyWeb,
    private val session: SpotifySession,
    private val timeZone: () -> String = { TimeZone.getDefault().id },
    private val maxPages: Int = MAX_PAGES,
) {
    init {
        require(maxPages > 0) { "a page limit must be positive, got $maxPages" }
    }

    val username: String get() = session.credentials?.username.orEmpty()

    val likedSongsUri: String get() = "spotify:user:$username:collection"

    suspend fun tracks(uris: List<String>): List<Track> {
        val entities = metadata(uris.distinct(), ExtensionKind.TRACK_V4)
        return uris.mapNotNull { uri ->
            entities[uri]?.let(Metadata::parseTrack)?.let(SpotifyTracks::trackOf)
        }
    }

    suspend fun likedTracks(): List<Track> =
        tracks(collection(COLLECTION_SET).withPrefix(Ids.TRACK_URI_PREFIX))

    suspend fun playlists(): List<SpotifyItem> {
        val path = "/playlist/v2/user/${percentEncoded(username)}/rootlist?decorate=$ROOTLIST_DECORATION"
        val items = listItems(path, ROOTLIST_LABEL)
        return CatalogMessages.playlistsOf(items).map { SpotifyItem(it.uri, it.name) }
    }

    suspend fun playlistTracks(uri: String): List<Track> {
        val path = "/playlist/v2/playlist/${idOf(uri)}?decorate=$PLAYLIST_DECORATION"
        val items = listItems(path, path.substringBefore('?'))
        return tracks(items.map(ListItem::uri).withPrefix(Ids.TRACK_URI_PREFIX))
    }

    suspend fun savedAlbums(): List<SpotifyItem> =
        names(collection(COLLECTION_SET).withPrefix(Ids.ALBUM_URI_PREFIX), ExtensionKind.ALBUM_V4) {
            Metadata.parseAlbum(it)?.name
        }

    suspend fun followedArtists(): List<SpotifyItem> =
        names(collection(ARTIST_SET).withPrefix(Ids.ARTIST_URI_PREFIX), ExtensionKind.ARTIST_V4) {
            Metadata.parseArtist(it)?.name
        }

    suspend fun albumTracks(uri: String): List<Track> {
        val payload = metadata(listOf(uri), ExtensionKind.ALBUM_V4)[uri] ?: return emptyList()
        val album = Metadata.parseAlbum(payload) ?: return emptyList()
        return tracks(album.tracks.map(::trackUriOf))
    }

    suspend fun artistTopTracks(uri: String): List<Track> {
        val payload = metadata(listOf(uri), ExtensionKind.ARTIST_V4)[uri] ?: return emptyList()
        val artist = Metadata.parseArtist(payload) ?: return emptyList()
        return tracks(Metadata.topTracksIn(artist, session.country).take(TOP_TRACKS).map(::trackUriOf))
    }

    suspend fun forYou(): List<SpotifyItem> {
        val variables =
            buildJsonObject {
                put("homeEndUserIntegration", "INTEGRATION_DESKTOP")
                put("timeZone", timeZone().ifEmpty { "UTC" })
                put("sp_t", "")
                put("facet", "")
                put("sectionItemsLimit", FOR_YOU_LIMIT)
                put("includeEpisodeContentRatingsV2", true)
            }
        val data = pathfinder(HOME_OPERATION, HOME_HASHES, variables)
        return CatalogJson.madeForYou(data)
            ?: throw pathfinderUnreadable("$HOME_OPERATION without a section container")
    }

    suspend fun search(text: String): SpotifyFound {
        val term = text.trim()
        if (term.isEmpty()) return SpotifyFound()
        val variables =
            buildJsonObject {
                put("searchTerm", term)
                put("offset", 0)
                put("limit", SEARCH_LIMIT)
                put("numberOfTopResults", TOP_RESULTS)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
                put("includeAuthors", false)
            }
        val data = pathfinder(SEARCH_OPERATION, SEARCH_HASHES, variables)
        return CatalogJson.found(data) ?: throw pathfinderUnreadable("$SEARCH_OPERATION without searchV2")
    }

    suspend fun searchPick(text: String): SpotifyPick {
        val found = search(text)
        val best = found.best
        return when {
            found.kind == SpotifyFound.Kind.ARTIST && best != null ->
                SpotifyPick(best, best.uri, artistTopTracks(best.uri))

            found.kind == SpotifyFound.Kind.ALBUM && best != null ->
                SpotifyPick(best, best.uri, albumTracks(best.uri))

            else -> {
                val contextUri = found.trackUris.firstOrNull().orEmpty()
                SpotifyPick(best = null, contextUri = contextUri, tracks = tracks(found.trackUris))
            }
        }
    }

    suspend fun station(trackUri: String): ContextPage = context(
        WebRequest(path = STATION_PATH + idOf(trackUri), accept = SpotifyWeb.JSON, accountQuery = false),
    )

    suspend fun autoplay(contextUri: String, recentTrackUris: List<String>): ContextPage = context(
        WebRequest(
            path = AUTOPLAY_PATH,
            method = "POST",
            body = Bytes.of(CatalogMessages.autoplayRequest(contextUri, recentTrackUris)),
            accept = SpotifyWeb.JSON,
            accountQuery = false,
        ),
    )

    suspend fun nextPage(pageUrl: String): ContextPage {
        if (!pageUrl.startsWith(PAGE_PREFIX)) {
            throw SpotifyContentException("a next page that does not start with $PAGE_PREFIX")
        }
        val path = pageUrl.substring(PAGE_PREFIX.length - 1)
        val label = path.substringBefore('?').substringBeforeLast('/') + "/{page}"
        return context(WebRequest(path = path, accept = SpotifyWeb.JSON, accountQuery = false, label = label))
    }

    private suspend fun metadata(uris: List<String>, kind: ExtensionKind): Map<String, Bytes> {
        if (uris.isEmpty()) return emptyMap()
        val replies =
            coroutineScope {
                uris.chunked(METADATA_BATCH).map { batch -> async { metadataBatch(batch, kind) } }.awaitAll()
            }
        val found = HashMap<String, Bytes>()
        for (entity in replies.flatten()) {
            if (entity.found) found[entity.uri] = entity.payload
        }
        return found
    }

    private suspend fun metadataBatch(uris: List<String>, kind: ExtensionKind): List<MetadataEntity> {
        val request =
            WebRequest(path = METADATA_PATH, method = "POST", body = Bytes.of(Metadata.request(uris, kind)))
        return Metadata.parseReply(web.spclient(request))
            ?: throw unreadable(request, "entities that do not parse")
    }

    private suspend fun collection(set: String): List<String> {
        val uris = ArrayList<String>()
        var token = ""
        repeat(maxPages) {
            val body = CatalogMessages.collectionPageRequest(username, set, token, COLLECTION_PAGE)
            val request =
                WebRequest(
                    path = COLLECTION_PATH,
                    method = "POST",
                    body = Bytes.of(body),
                    contentType = CatalogMessages.COLLECTION_CONTENT_TYPE,
                    accept = CatalogMessages.COLLECTION_CONTENT_TYPE,
                    accountQuery = false,
                )
            val page =
                CatalogMessages.parseCollectionPage(web.spclient(request))
                    ?: throw unreadable(request, "a $set page that does not parse")
            page.items.mapTo(uris) { it.uri }
            if (page.last) return uris
            token = page.nextPageToken
        }
        throw web.unreadable("POST", COLLECTION_PATH, "$set set: more than $maxPages pages")
    }

    private suspend fun listItems(path: String, label: String): List<ListItem> {
        val items = ArrayList<ListItem>()
        var from = 0
        repeat(maxPages) {
            val request = WebRequest(path = "$path&from=$from&length=$LIST_PAGE", label = label)
            val page =
                CatalogMessages.parseListPage(web.spclient(request))
                    ?: throw unreadable(request, "a page from $from that does not parse")
            items += page.items
            if (!page.hasMoreAfter(from, LIST_PAGE)) return items
            from += LIST_PAGE
        }
        throw web.unreadable("GET", label, "a list of more than $maxPages pages")
    }

    private suspend fun names(
        uris: List<String>,
        kind: ExtensionKind,
        nameOf: (Bytes) -> String?,
    ): List<SpotifyItem> {
        val entities = metadata(uris, kind)
        return uris.mapNotNull { uri ->
            val name = entities[uri]?.let(nameOf).orEmpty()
            if (name.isEmpty()) null else SpotifyItem(uri, name)
        }
    }

    private suspend fun pathfinder(
        operation: String,
        hashes: List<String>,
        variables: JsonObject,
    ): JsonObject {
        for ((attempt, hash) in hashes.withIndex()) {
            val hashLeft = attempt < hashes.lastIndex
            val body =
                try {
                    web.pathfinder(queryOf(operation, hash, variables))
                } catch (failed: SpotifyHttpException) {
                    val stale = failed.status in STALE_STATUSES && failed.path == SpotifyWeb.PATHFINDER_PATH
                    if (!stale) throw failed
                    if (hashLeft) continue
                    web.report(SpotifyProblem.QueryOutdated)
                    throw failed
                }
            val data = CatalogJson.pathfinderData(body)
            if (data != null) return data
            if (hashLeft) continue
            web.report(SpotifyProblem.QueryOutdated)
            throw QueryOutdatedException(operation)
        }
        throw QueryOutdatedException(operation)
    }

    private suspend fun context(request: WebRequest): ContextPage {
        val context =
            CatalogJson.context(web.spclient(request))
                ?: throw unreadable(request, "a context that cannot be read")
        return ContextPage(tracks(context.trackUris), context.next)
    }

    private fun unreadable(request: WebRequest, detail: String): SpotifyContentException =
        web.unreadable(request.method, request.label, detail)

    private fun pathfinderUnreadable(detail: String): SpotifyContentException =
        web.unreadable("POST", SpotifyWeb.PATHFINDER_PATH, detail)

    private fun List<String>.withPrefix(prefix: String): List<String> = filter { it.startsWith(prefix) }

    companion object {
        const val METADATA_BATCH = 100
        const val MAX_PAGES = 1000
        const val COLLECTION_PAGE = 300
        const val LIST_PAGE = 1000
        const val SEARCH_LIMIT = 50
        const val TOP_RESULTS = 5
        const val TOP_TRACKS = 10
        const val FOR_YOU_LIMIT = 20
        const val METADATA_PATH = "/extended-metadata/v0/extended-metadata"
        const val COLLECTION_PATH = "/collection/v2/paging"
        const val STATION_PATH = "/context-resolve/v1/spotify:station:track:"
        const val AUTOPLAY_PATH = "/context-resolve/v1/autoplay"
        const val ROOTLIST_LABEL = "/playlist/v2/user/{username}/rootlist"
        const val ROOTLIST_DECORATION = "revision,attributes,length,owner,capabilities,status_code"
        const val PLAYLIST_DECORATION = "revision,attributes,length,owner"
        const val COLLECTION_SET = "collection"
        const val ARTIST_SET = "artist"
        const val PAGE_PREFIX = "hm://"
        const val HOME_OPERATION = "home"
        const val SEARCH_OPERATION = "searchDesktop"
        val STALE_STATUSES = setOf(400, 405, 412)
        val HOME_HASHES = listOf("76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a")
        val SEARCH_HASHES =
            listOf(
                "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03",
                "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c",
                "3c9d3f60dac5dea3876b6db3f534192b1c1d90032c4233c1bbaba526db41eb31",
                "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
            )

        fun idOf(uri: String): String = uri.split(':').getOrElse(2) { "" }

        fun trackUriOf(gid: Bytes): String = Ids.TRACK_URI_PREFIX + Ids.base62FromGid(gid)

        private fun queryOf(operation: String, hash: String, variables: JsonObject): String =
            buildJsonObject {
                put("variables", variables)
                put("operationName", operation)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", hash)
                    }
                }
            }.toString()

        private fun percentEncoded(text: String): String = buildString {
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                val value = byte.toInt() and 0xff
                val char = value.toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UNRESERVED) {
                    append(char)
                } else {
                    append('%').append(HEX[value ushr 4]).append(HEX[value and 0x0f])
                }
            }
        }

        private const val UNRESERVED = "-._~"
        private const val HEX = "0123456789ABCDEF"
    }
}
