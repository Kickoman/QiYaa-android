package io.github.kickoman.qiyaa.yandex

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class Library(val api: YandexApi) : AccountGateway {
    private val mutableAccount = MutableStateFlow(Account())
    val account: StateFlow<Account> = mutableAccount.asStateFlow()

    private val mutableLikedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = mutableLikedIds.asStateFlow()

    val isLoggedIn: Boolean get() = mutableAccount.value.isValid

    fun isLiked(trackId: String): Boolean = trackId in mutableLikedIds.value

    override var token: String
        get() = api.token
        set(value) {
            api.token = value
        }

    override val tokenRejections: Flow<HttpException> get() = api.tokenRejections

    override suspend fun connectAccount(): Account {
        val account = api.accountStatus()
        mutableAccount.value = account
        return account
    }

    override suspend fun preloadLikes() {
        likedTrackIds()
    }

    override fun forget() = logout()

    fun logout() {
        mutableAccount.value = Account()
        mutableLikedIds.value = emptySet()
        api.token = ""
    }

    suspend fun tracksByIds(ids: List<String>): List<Track> {
        val tracks = ArrayList<Track>(ids.size)
        for (chunk in ids.chunked(TRACKS_PER_REQUEST)) tracks += api.tracks(chunk)
        return tracks
    }

    suspend fun likedTrackIds(): List<String> {
        val result = api.getJson(userPath("likes/tracks"))
        val ids =
            result.objectOrEmpty["library"].objectOrEmpty["tracks"].arrayOrEmpty.mapNotNull { item ->
                idString(item.objectOrEmpty["id"]).ifEmpty { null }
            }
        mutableLikedIds.value = ids.toSet()
        return ids
    }

    suspend fun likedTracks(): List<Track> = tracksByIds(likedTrackIds())

    suspend fun userPlaylists(): List<PlaylistRef> =
        api.getJson(userPath("playlists/list")).arrayOrEmpty.mapNotNull { element ->
            val item = element.objectOrEmpty
            val owner = idString(item["uid"]).ifEmpty { idString(item["owner"].objectOrEmpty["uid"]) }
            val kind = idString(item["kind"])
            if (kind.isEmpty()) return@mapNotNull null
            PlaylistRef(owner, kind, item.string("title"), item.int("trackCount", 0))
        }

    suspend fun playlistTracks(playlist: PlaylistRef): List<Track> {
        val result = api.getJson("/users/${playlist.ownerUid}/playlists/${playlist.kind}")
        val items = result.objectOrEmpty["tracks"].arrayOrEmpty
        val embedded = TrackParsing.parseTrackArray(items)
        if (embedded.all { it.title.isNotEmpty() }) return embedded
        return tracksByIds(items.map { idString(it.objectOrEmpty["id"]) })
    }

    suspend fun likedArtists(): List<NamedRef> =
        api.getJson(userPath("likes/artists")).arrayOrEmpty.mapNotNull { element ->
            val item = unwrap(element.objectOrEmpty, "artist")
            val id = idString(item["id"])
            if (id.isEmpty()) null else NamedRef(id, item.string("name"))
        }

    suspend fun artistTopTracks(artistId: String): List<Track> {
        val result = api.getJson("/artists/$artistId/track-ids-by-rating")
        val ids = result.objectOrEmpty["tracks"].arrayOrEmpty.map { idString(it) }
        return tracksByIds(ids.take(ARTIST_TOP_LIMIT))
    }

    suspend fun likedAlbums(): List<NamedRef> {
        val ids =
            api.getJson(userPath("likes/albums")).arrayOrEmpty.mapNotNull { element ->
                idString(unwrap(element.objectOrEmpty, "album")["id"]).ifEmpty { null }
            }
        if (ids.isEmpty()) return emptyList()
        val albums = api.postForm("/albums", listOf("album-ids" to ids.joinToString(",")))
        return albums.arrayOrEmpty.mapNotNull { element ->
            val item = element.objectOrEmpty
            if (item.string("type") == "podcast") return@mapNotNull null
            val firstArtist = item["artists"].arrayOrEmpty.firstOrNull()?.objectOrEmpty?.string("name")
            val title = item.string("title")
            NamedRef(idString(item["id"]), if (firstArtist == null) title else "$firstArtist - $title")
        }
    }

    suspend fun albumTracks(albumId: String): List<Track> {
        val result = api.getJson("/albums/$albumId/with-tracks")
        return result.objectOrEmpty["volumes"].arrayOrEmpty.flatMap { volume ->
            TrackParsing.parseTrackArray(volume).map { track ->
                if (track.albumId.isEmpty()) track.copy(albumId = albumId) else track
            }
        }
    }

    suspend fun stations(): List<Station> =
        api.getJson("/rotor/stations/list", mapOf("language" to "ru")).arrayOrEmpty.mapNotNull { element ->
            val station = element.objectOrEmpty["station"].objectOrEmpty
            val id = station["id"].objectOrEmpty
            val type = id.string("type")
            if (type.isEmpty()) null else Station("$type:${id.string("tag")}", type, station.string("name"))
        }

    suspend fun startWave(seeds: List<String>): WaveBatch {
        val body =
            buildJsonObject {
                putJsonArray("seeds") { seeds.forEach { add(JsonPrimitive(it)) } }
                put("includeTracksInResponse", true)
                put("includeWaveModel", true)
                put("interactive", true)
            }
        val batch = parseWaveBatch(api.postJson("/rotor/session/new", body))
        if (batch.sessionId.isEmpty()) {
            throw MalformedResponseException("POST", "/rotor/session/new", "no radioSessionId in the result")
        }
        return batch
    }

    suspend fun moreWave(sessionId: String, queue: List<String>): WaveBatch {
        val body = buildJsonObject { putJsonArray("queue") { queue.forEach { add(JsonPrimitive(it)) } } }
        val batch = parseWaveBatch(api.postJson("/rotor/session/$sessionId/tracks", body))
        return if (batch.sessionId.isEmpty()) batch.copy(sessionId = sessionId) else batch
    }

    suspend fun search(text: String): SearchResult {
        val query = mapOf("text" to text, "type" to "all", "page" to "0")
        val result = api.getJson("/search", query).objectOrEmpty
        val best = result["best"].objectOrEmpty
        val bestItem = best["result"].objectOrEmpty
        return SearchResult(
            bestType = best.string("type"),
            bestId = idString(bestItem["id"]),
            bestName = if (bestItem.containsKey(
                    "name",
                )
            ) {
                bestItem.string("name")
            } else {
                bestItem.string("title")
            },
            tracks = TrackParsing.parseTrackArray(result["tracks"].objectOrEmpty["results"]),
        )
    }

    suspend fun setLiked(trackId: String, liked: Boolean) {
        val path = userPath(if (liked) "likes/tracks/add-multiple" else "likes/tracks/remove")
        api.postForm(path, listOf("track-ids" to trackId))
        mutableLikedIds.update { if (liked) it + trackId else it - trackId }
    }

    suspend fun dislike(trackId: String) {
        api.postForm(userPath("dislikes/tracks/add-multiple"), listOf("track-ids" to trackId))
        mutableLikedIds.update { it - trackId }
    }

    private fun userPath(rest: String): String {
        val uid = mutableAccount.value.uid
        if (uid.isEmpty()) throw NotSignedInException("/users/{uid}/$rest")
        return "/users/$uid/$rest"
    }

    private fun unwrap(item: JsonObject, key: String): JsonObject = item[key] as? JsonObject ?: item

    companion object {
        const val TRACKS_PER_REQUEST = 250
        const val ARTIST_TOP_LIMIT = 100

        fun parseWaveBatch(result: JsonElement): WaveBatch {
            val item = result.objectOrEmpty
            return WaveBatch(
                sessionId = item.string("radioSessionId"),
                batchId = item.string("batchId"),
                tracks = TrackParsing.parseTrackArray(item["sequence"]),
            )
        }

        fun stationGroupKey(type: String): String = if (type == "user") "personal" else type
    }
}
