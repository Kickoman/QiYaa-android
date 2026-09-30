package io.github.kickoman.qiyaa.jam

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

sealed interface Decoded<out T> {
    data class Message<T>(val message: T) : Decoded<T>

    data object UnknownType : Decoded<Nothing>

    data class Invalid(val problem: String) : Decoded<Nothing>
}

object JamCodec {
    val json =
        Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            explicitNulls = false
            encodeDefaults = true
        }

    val REASONS: Set<String> =
        setOf(
            "bad-key", "bad-secret", "room-not-found", "room-full", "join-closed", "kicked", "rate-limited",
            "queue-limit", "duplicate", "not-allowed", "stale", "unknown-track", "track-unavailable",
            "host-offline", "host-timeout", "host-error", "invalid-message", "update-required", "server-full",
        )

    val CLIENT_TYPES: Set<String> =
        setOf(
            "hello", "create", "resume", "playing", "started", "add", "pin", "remove", "kick", "settings",
            "rotateLink", "end", "searchResult", "validateResult", "join", "search", "skip",
        )
    val SERVER_TYPES: Set<String> =
        setOf(
            "welcome", "rejected", "ack", "created", "resumed", "joined", "searchResults", "linkRotated",
            "searchRequest", "validateRequest", "command", "snapshot", "state", "ended", "kicked",
        )
    private val SECRET_FIELDS = setOf("hostKey", "hostSecret", "joinSecret", "participantId")

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    fun encodeServer(message: ServerMessage): String =
        json.encodeToString(ServerMessage.serializer(), message)

    fun decodeServer(text: String): Decoded<ServerMessage> = decode(text, SERVER_TYPES) { element ->
        json.decodeFromJsonElement(ServerMessage.serializer(), element).also { check(it, element) }
    }

    fun decodeClient(text: String): Decoded<ClientMessage> = decode(text, CLIENT_TYPES) { element ->
        json.decodeFromJsonElement(ClientMessage.serializer(), element).also { check(it) }
    }

    fun isUnknownReason(decoded: Decoded<ServerMessage>): Boolean =
        decoded is Decoded.Invalid && decoded.problem.startsWith("rejected: $UNKNOWN_REASON")

    private fun <T> decode(text: String, types: Set<String>, parse: (JsonObject) -> T): Decoded<T> {
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (failed: SerializationException) {
                return Decoded.Invalid("not JSON: ${failed.message}")
            }
        if (element !is JsonObject) return Decoded.Invalid("not a JSON object")
        val type = (element["type"] as? JsonPrimitive)?.contentOrNull
        if (type == null || type !in types) return Decoded.UnknownType
        return try {
            Decoded.Message(parse(element))
        } catch (failed: SerializationException) {
            Decoded.Invalid("$type: ${failed.message}")
        } catch (failed: IllegalArgumentException) {
            Decoded.Invalid("$type: ${failed.message}")
        }
    }

    private fun check(message: ServerMessage, raw: JsonObject) {
        when (message) {
            is Welcome -> {
                atLeast("protocol", message.protocol.toLong(), 1)
                time("serverTime", message.serverTime)
            }
            is Rejected -> {
                message.id?.let { pattern("id", it, requestId) }
                message.detail?.let { require(it.length <= 200) { "detail is over 200 characters" } }
                message.serverProtocol?.let { atLeast("serverProtocol", it.toLong(), 1) }
                require(message.reason in REASONS) { "$UNKNOWN_REASON ${message.reason}" }
            }
            is Ack -> pattern("id", message.id, requestId)
            is Created -> {
                pattern("id", message.id, requestId)
                pattern("roomId", message.roomId, roomId)
                pattern("hostSecret", message.hostSecret, hostSecret)
                pattern("joinSecret", message.joinSecret, joinSecret)
                joinUrl(message.joinUrl)
                pattern("publicId", message.publicId, publicId)
            }
            is Resumed -> pattern("id", message.id, requestId)
            is Joined -> {
                pattern("id", message.id, requestId)
                pattern("publicId", message.publicId, publicId)
            }
            is SearchResults -> {
                pattern("id", message.id, requestId)
                tracks(message.tracks, 20)
            }
            is LinkRotated -> {
                pattern("id", message.id, requestId)
                pattern("joinSecret", message.joinSecret, joinSecret)
                joinUrl(message.joinUrl)
            }
            is SearchRequest -> {
                pattern("requestId", message.requestId, requestId)
                searchText(message.text)
            }
            is ValidateRequest -> {
                pattern("requestId", message.requestId, requestId)
                require(message.trackIds.size in 1..20) { "trackIds has ${message.trackIds.size} entries" }
                message.trackIds.forEach { pattern("trackIds[]", it, catalogId) }
            }
            is Command -> pattern("itemId", message.itemId, itemId)
            is Snapshot -> snapshot(message.data)
            is State -> {
                atLeast("version", message.version, 1)
                time("serverTime", message.serverTime)
                noSecrets(raw, "state")
                room(message.room)
            }
            is Ended, Kicked -> Unit
        }
    }

    private fun check(message: ClientMessage) {
        when (message) {
            is Hello -> {
                atLeast("protocol", message.protocol.toLong(), 1)
                pattern("appVersion", message.appVersion, appVersion)
            }
            is Create -> {
                pattern("id", message.id, requestId)
                pattern("hostKey", message.hostKey, hostKey)
                name("hostName", message.hostName)
                message.settings?.let(::settingsPatch)
            }
            is Resume -> {
                pattern("id", message.id, requestId)
                pattern("roomId", message.roomId, roomId)
                pattern("hostSecret", message.hostSecret, hostSecret)
                pattern("hostKey", message.hostKey, hostKey)
                if (message.snapshot !is JsonNull) snapshot(message.snapshot)
                require(message.outbox.size <= 500) { "outbox has ${message.outbox.size} events" }
                message.outbox.forEach {
                    require(it.type == OutboxEntry.STARTED) { "outbox holds a ${it.type}, not started" }
                    pattern("outbox[].itemId", it.itemId, itemId)
                }
            }
            is Playing -> {
                atLeast("positionMs", message.positionMs, 0)
                message.itemId?.let { pattern("itemId", it, itemId) }
                message.track?.let(::track)
                when (message.source) {
                    NowPlayingSource.ITEM -> require(message.itemId != null) { "source item needs itemId" }
                    NowPlayingSource.WAVE -> require(message.track != null) { "source wave needs track" }
                    NowPlayingSource.IDLE -> Unit
                }
            }
            is Started -> pattern("itemId", message.itemId, itemId)
            is Add -> {
                pattern("id", message.id, requestId)
                require((message.trackId == null) != (message.track == null)) {
                    "exactly one of trackId and track"
                }
                message.trackId?.let { pattern("trackId", it, catalogId) }
                message.track?.let(::track)
            }
            is Pin -> requestAndItem(message.id, message.itemId)
            is Remove -> requestAndItem(message.id, message.itemId)
            is Skip -> requestAndItem(message.id, message.itemId)
            is Kick -> {
                pattern("id", message.id, requestId)
                pattern("publicId", message.publicId, publicId)
            }
            is ChangeSettings -> {
                pattern("id", message.id, requestId)
                settingsPatch(message.settings)
                require(message.settings != JamSettingsPatch()) { "settings changes nothing" }
            }
            is RotateLink -> pattern("id", message.id, requestId)
            is End -> pattern("id", message.id, requestId)
            is SearchResult -> {
                pattern("requestId", message.requestId, requestId)
                require((message.tracks == null) != (message.error == null)) {
                    "exactly one of tracks and error"
                }
                message.tracks?.let { tracks(it, 20) }
            }
            is ValidateResult -> {
                pattern("requestId", message.requestId, requestId)
                require(message.results.size in 1..20) { "results has ${message.results.size} entries" }
                message.results.forEach {
                    pattern("results[].trackId", it.trackId, catalogId)
                    require((it.track == null) != (it.reason == null)) {
                        "a result has either track or reason"
                    }
                    it.track?.let(::track)
                }
            }
            is Join -> {
                pattern("id", message.id, requestId)
                pattern("roomId", message.roomId, roomId)
                pattern("joinSecret", message.joinSecret, joinSecret)
                pattern("participantId", message.participantId, participantId)
                name("name", message.name)
            }
            is Search -> {
                pattern("id", message.id, requestId)
                searchText(message.text)
            }
        }
    }

    private fun requestAndItem(id: String, item: String) {
        pattern("id", id, requestId)
        pattern("itemId", item, itemId)
    }

    private fun room(room: JamRoom) {
        pattern("room.id", room.id, roomId)
        name("hostName", room.hostName)
        settings(room.settings)
        pattern("you.publicId", room.you.publicId, publicId)
        require(room.participants.size <= 31) { "${room.participants.size} participants" }
        room.participants.forEach {
            pattern("participants[].publicId", it.publicId, publicId)
            name("participants[].name", it.name)
            atLeast("participants[].pending", it.pending.toLong(), 0)
        }
        nowPlaying(room.nowPlaying)
        require(room.queue.size <= 300) { "queue has ${room.queue.size} items" }
        room.queue.forEach {
            pattern("queue[].itemId", it.itemId, itemId)
            track(it.track)
            pattern("queue[].addedBy", it.addedBy, publicId)
            time("queue[].addedAt", it.addedAt)
        }
        require(room.recent.size <= 10) { "recent has ${room.recent.size} items" }
        room.recent.forEach {
            pattern("recent[].itemId", it.itemId, itemId)
            track(it.track)
            pattern("recent[].addedBy", it.addedBy, publicId)
            time("recent[].playedAt", it.playedAt)
        }
        val seeds = room.fallback.seeds
        require(seeds.size <= 5 && seeds.toSet().size == seeds.size) { "seeds must be up to 5 distinct" }
        seeds.forEach { pattern("seeds[]", it, seed) }
        atLeast("seedsVersion", room.fallback.seedsVersion.toLong(), 0)
    }

    private fun nowPlaying(nowPlaying: JamNowPlaying) {
        atLeast("positionMs", nowPlaying.positionMs, 0)
        time("reportedAt", nowPlaying.reportedAt)
        nowPlaying.itemId?.let { pattern("nowPlaying.itemId", it, itemId) }
        nowPlaying.addedBy?.let { pattern("nowPlaying.addedBy", it, publicId) }
        nowPlaying.track?.let(::track)
        when (nowPlaying.source) {
            NowPlayingSource.ITEM ->
                require(nowPlaying.itemId != null && nowPlaying.track != null && nowPlaying.addedBy != null) {
                    "nowPlaying item needs itemId, track and addedBy"
                }
            NowPlayingSource.WAVE -> require(nowPlaying.track != null) { "nowPlaying wave needs track" }
            NowPlayingSource.IDLE -> Unit
        }
    }

    private fun snapshot(data: JsonElement) {
        require(data is JsonObject) { "snapshot is not an object" }
        require((data["format"] as? JsonPrimitive)?.intOrNull == 1) { "snapshot format is not 1" }
        require(data["room"] is JsonObject) { "snapshot has no room" }
        noSecrets(data, "snapshot")
    }

    private fun noSecrets(element: JsonElement, where: String) {
        when (element) {
            is JsonObject ->
                element.forEach { (key, value) ->
                    require(key !in SECRET_FIELDS) { "$where carries $key" }
                    noSecrets(value, where)
                }
            is JsonArray -> element.forEach { noSecrets(it, where) }
            else -> Unit
        }
    }

    private fun settings(settings: JamSettings) {
        require(settings.maxPendingPerGuest in 1..50) { "maxPendingPerGuest ${settings.maxPendingPerGuest}" }
    }

    private fun settingsPatch(patch: JamSettingsPatch) {
        patch.maxPendingPerGuest?.let { require(it in 1..50) { "maxPendingPerGuest $it" } }
    }

    private fun tracks(tracks: List<JamTrack>, limit: Int) {
        require(tracks.size <= limit) { "${tracks.size} tracks, the limit is $limit" }
        tracks.forEach(::track)
    }

    private fun track(track: JamTrack) {
        pattern("track.id", track.id, catalogId)
        track.albumId?.let { pattern("track.albumId", it, catalogId) }
        require(track.title.codePointCount() in 1..150 && noControl(track.title)) { "bad track title" }
        require(track.artists.size <= 10) { "${track.artists.size} artists" }
        track.artists.forEach { require(it.codePointCount() in 1..64 && noControl(it)) { "bad artist $it" } }
        require(track.durationMs in 0..86_400_000) { "durationMs ${track.durationMs}" }
        track.coverUri?.let {
            require(it.length <= 300 && coverUri.matches(it)) { "coverUri is not a %% template" }
        }
    }

    private fun name(field: String, value: String) {
        require(value.codePointCount() in 1..24 && nameShape.matches(value)) {
            "$field is not a trimmed 1-24 name"
        }
    }

    private fun searchText(text: String) {
        require(text.codePointCount() in 1..100 && noControl(text) && text.isNotBlank()) { "bad search text" }
    }

    private fun joinUrl(url: String) {
        require(url.length <= 200 && joinUrl.matches(url)) { "joinUrl $url" }
    }

    private fun pattern(field: String, value: String, regex: Regex) {
        require(regex.matches(value)) { "$field does not match ${regex.pattern}" }
    }

    private fun atLeast(field: String, value: Long, minimum: Long) {
        require(value >= minimum) { "$field is $value, below $minimum" }
    }

    private fun time(field: String, value: Long) = atLeast(field, value, 0)

    private fun noControl(text: String): Boolean = text.none { it.code < 0x20 || it.code == 0x7F }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private const val UNKNOWN_REASON = "unknown reason"

    private val requestId = Regex("^[A-Za-z0-9_-]{1,36}$")
    private val roomId = Regex("^[0-9a-hjkmnp-tv-z]{8}$")
    private val publicId = Regex("^[0-9a-hjkmnp-tv-z]{6}$")
    private val itemId = Regex("^i[1-9][0-9]{0,8}$")
    private val catalogId = Regex("^[0-9A-Za-z_-]{1,64}$")
    private val joinSecret = Regex("^[A-Za-z0-9_-]{22}$")
    private val hostSecret = Regex("^[A-Za-z0-9_-]{43}$")
    private val hostKey = Regex("^qjk_[A-Za-z0-9_-]{43}$")
    private val participantId = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val appVersion = Regex("^[0-9A-Za-z.+_-]{1,32}$")
    private val seed = Regex("^track:[0-9A-Za-z_-]{1,64}$")
    private val coverUri = Regex("^\\S*%%\\S*$")
    private val joinUrl =
        Regex("^https?://[^\\s#/]+(?:/[^\\s#]*)?/j/[0-9a-hjkmnp-tv-z]{8}#[A-Za-z0-9_-]{22}$")
    private val nameShape = Regex("^[^\\s\\x00-\\x1F\\x7F](?:[^\\x00-\\x1F\\x7F]*[^\\s\\x00-\\x1F\\x7F])?$")
}
