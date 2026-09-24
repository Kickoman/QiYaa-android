package io.github.kickoman.qiyaa.yandex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Port of src/yandex/Library.cpp: the user's library on top of [YandexApi]. */
class Library(val api: YandexApi) {
    private val _account = MutableStateFlow(Account())
    val account: StateFlow<Account> = _account.asStateFlow()

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    val isLoggedIn: Boolean get() = _account.value.isValid
    fun isLiked(trackId: String) = trackId in _likedIds.value

    private fun userPath(rest: String) = "/users/${_account.value.uid}/$rest"

    suspend fun connectAccount(): Account {
        val acc = api.accountStatus()
        _account.value = acc
        return acc
    }

    fun logout() {
        _account.value = Account()
        _likedIds.value = emptySet()
        api.token = ""
    }

    /** Fetches full track objects in chunks of 250, keeping the order. */
    suspend fun tracksByIds(ids: List<String>): List<Track> {
        val out = ArrayList<Track>(ids.size)
        for (chunk in ids.chunked(TRACKS_PER_REQUEST)) out += api.tracks(chunk)
        return out
    }

    suspend fun likedTrackIds(): List<String> {
        val r = api.getJson(userPath("likes/tracks"))
        val ids = r.obj["library"].obj["tracks"].arr.mapNotNull { v -> idString(v.obj["id"]).ifEmpty { null } }
        _likedIds.value = ids.toSet()
        return ids
    }

    suspend fun likedTracks(): List<Track> = tracksByIds(likedTrackIds())

    suspend fun userPlaylists(): List<PlaylistRef> =
        api.getJson(userPath("playlists/list")).arr.mapNotNull { v ->
            val o = v.obj
            var owner = idString(o["uid"])
            if (owner.isEmpty()) owner = idString(o["owner"].obj["uid"])
            val kind = idString(o["kind"])
            if (kind.isEmpty()) return@mapNotNull null
            PlaylistRef(owner, kind, o.str("title"), (o["trackCount"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0)
        }

    suspend fun playlistTracks(playlist: PlaylistRef): List<Track> {
        val r = api.getJson("/users/${playlist.ownerUid}/playlists/${playlist.kind}")
        val items = r.obj["tracks"].arr
        // Items usually embed full track objects; fall back to fetching by id.
        val embedded = YandexApi.parseTrackArray(items)
        if (embedded.all { it.title.isNotEmpty() }) return embedded
        return tracksByIds(items.map { idString(it.obj["id"]) })
    }

    suspend fun likedArtists(): List<NamedRef> =
        api.getJson(userPath("likes/artists")).arr.mapNotNull { v ->
            var o = v.obj
            (o["artist"] as? JsonObject)?.let { o = it }
            val id = idString(o["id"])
            if (id.isEmpty()) null else NamedRef(id, o.str("name"))
        }

    suspend fun artistTopTracks(artistId: String): List<Track> {
        val r = api.getJson("/artists/$artistId/track-ids-by-rating")
        val ids = r.obj["tracks"].arr.map { idString(it) }
        return tracksByIds(ids.take(ARTIST_TOP_LIMIT))
    }

    suspend fun likedAlbums(): List<NamedRef> {
        val ids = api.getJson(userPath("likes/albums")).arr.mapNotNull { v ->
            var o = v.obj
            (o["album"] as? JsonObject)?.let { o = it }
            idString(o["id"]).ifEmpty { null }
        }
        if (ids.isEmpty()) return emptyList()
        return api.postForm("/albums", listOf("album-ids" to ids.joinToString(","))).arr.mapNotNull { v ->
            val o = v.obj
            if (o.str("type") == "podcast") return@mapNotNull null
            val artists = o["artists"] as? JsonArray
            var name = o.str("title")
            if (!artists.isNullOrEmpty()) name = artists.first().obj.str("name") + " - " + name
            NamedRef(idString(o["id"]), name)
        }
    }

    suspend fun albumTracks(albumId: String): List<Track> {
        val r = api.getJson("/albums/$albumId/with-tracks")
        return r.obj["volumes"].arr.flatMap { vol ->
            YandexApi.parseTrackArray(vol).map { t -> if (t.albumId.isEmpty()) t.copy(albumId = albumId) else t }
        }
    }

    suspend fun stations(): List<Station> =
        api.getJson("/rotor/stations/list", mapOf("language" to "ru")).arr.mapNotNull { v ->
            val st = v.obj["station"].obj
            val id = st["id"].obj
            val type = id.str("type")
            if (type.isEmpty()) null else Station("$type:${id.str("tag")}", type, st.str("name"))
        }

    suspend fun startWave(seeds: List<String>): WaveBatch {
        val body = buildJsonObject {
            putJsonArray("seeds") { seeds.forEach { add(JsonPrimitive(it)) } }
            put("includeTracksInResponse", true)
            put("includeWaveModel", true)
            put("interactive", true)
        }
        val b = parseWaveBatch(api.postJson("/rotor/session/new", body))
        if (b.sessionId.isEmpty()) throw ApiException("no radio session")
        return b
    }

    suspend fun moreWave(sessionId: String, queue: List<String>): WaveBatch {
        val body = buildJsonObject { putJsonArray("queue") { queue.forEach { add(JsonPrimitive(it)) } } }
        val b = parseWaveBatch(api.postJson("/rotor/session/$sessionId/tracks", body))
        return if (b.sessionId.isEmpty()) b.copy(sessionId = sessionId) else b
    }

    suspend fun search(text: String): SearchResult {
        val o = api.getJson("/search", mapOf("text" to text, "type" to "all", "page" to "0")).obj
        val best = o["best"].obj
        val item = best["result"].obj
        return SearchResult(
            bestType = best.str("type"),
            bestId = idString(item["id"]),
            bestName = if (item.containsKey("name")) item.str("name") else item.str("title"),
            tracks = YandexApi.parseTrackArray(o["tracks"].obj["results"]),
        )
    }

    suspend fun setLiked(trackId: String, liked: Boolean) {
        api.postForm(userPath(if (liked) "likes/tracks/add-multiple" else "likes/tracks/remove"), listOf("track-ids" to trackId))
        _likedIds.update { if (liked) it + trackId else it - trackId }
    }

    suspend fun dislike(trackId: String) {
        api.postForm(userPath("dislikes/tracks/add-multiple"), listOf("track-ids" to trackId))
        _likedIds.update { it - trackId }
    }

    companion object {
        const val TRACKS_PER_REQUEST = 250
        const val ARTIST_TOP_LIMIT = 100

        fun parseWaveBatch(result: kotlinx.serialization.json.JsonElement): WaveBatch {
            val r = result.obj
            return WaveBatch(r.str("radioSessionId"), r.str("batchId"), YandexApi.parseTrackArray(r["sequence"]))
        }

        /** Group label for the Stations screen (port of stationTypeTitle in LibraryMenu.cpp). */
        fun stationGroupKey(type: String): String = when (type) {
            "user", "personal" -> "personal"
            "genre" -> "genre"
            "mood" -> "mood"
            "activity" -> "activity"
            "epoch" -> "epoch"
            "local" -> "local"
            "author" -> "author"
            else -> type
        }
    }
}
