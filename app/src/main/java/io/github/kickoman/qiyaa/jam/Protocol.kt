package io.github.kickoman.qiyaa.jam

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

const val JAM_PROTOCOL = 1

@Serializable
data class JamTrack(
    val id: String,
    val albumId: String? = null,
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
    val coverUri: String? = null,
)

@Serializable
enum class JamOrder {
    @SerialName("round-robin")
    ROUND_ROBIN,

    @SerialName("fifo")
    FIFO,
}

@Serializable
data class JamSettings(
    val order: JamOrder,
    val guestsCanSkip: Boolean,
    val joinOpen: Boolean,
    val maxPendingPerGuest: Int,
)

@Serializable
data class JamSettingsPatch(
    val order: JamOrder? = null,
    val guestsCanSkip: Boolean? = null,
    val joinOpen: Boolean? = null,
    val maxPendingPerGuest: Int? = null,
)

@Serializable
enum class JamApp {
    @SerialName("desktop")
    DESKTOP,

    @SerialName("android")
    ANDROID,

    @SerialName("web")
    WEB,
}

@Serializable
enum class NowPlayingSource {
    @SerialName("item")
    ITEM,

    @SerialName("wave")
    WAVE,

    @SerialName("idle")
    IDLE,
}

@Serializable
enum class ParticipantKind {
    @SerialName("host")
    HOST,

    @SerialName("web")
    WEB,

    @SerialName("qiyaa")
    QIYAA,
}

@Serializable
data class JamParticipant(
    val publicId: String,
    val name: String,
    val kind: ParticipantKind,
    val online: Boolean,
    val pending: Int,
)

@Serializable
data class JamNowPlaying(
    val source: NowPlayingSource,
    val itemId: String? = null,
    val track: JamTrack? = null,
    val addedBy: String? = null,
    val positionMs: Long,
    val paused: Boolean,
    val reportedAt: Long,
)

@Serializable
data class JamQueueItem(
    val itemId: String,
    val track: JamTrack,
    val addedBy: String,
    val addedAt: Long,
    val pinned: Boolean,
)

@Serializable
data class JamRecentItem(val itemId: String, val track: JamTrack, val addedBy: String, val playedAt: Long)

@Serializable
data class JamFallback(val seeds: List<String>, val seedsVersion: Int)

@Serializable
data class JamYou(val publicId: String, val isHost: Boolean)

@Serializable
data class JamRoom(
    val id: String,
    val hostName: String,
    val hostOnline: Boolean,
    val settings: JamSettings,
    val you: JamYou,
    val participants: List<JamParticipant>,
    val nowPlaying: JamNowPlaying,
    val queue: List<JamQueueItem>,
    val recent: List<JamRecentItem>,
    val fallback: JamFallback,
)

@Serializable
data class OutboxEntry(val type: String = STARTED, val itemId: String) {
    companion object {
        const val STARTED = "started"
    }
}

@Serializable
enum class SearchError {
    @SerialName("failed")
    FAILED,

    @SerialName("unauthorized")
    UNAUTHORIZED,
}

@Serializable
enum class ValidateReason {
    @SerialName("track-unavailable")
    TRACK_UNAVAILABLE,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class ValidateEntry(val trackId: String, val track: JamTrack? = null, val reason: ValidateReason? = null)

@Serializable
enum class CommandKind {
    @SerialName("skip")
    SKIP,
}

@Serializable
enum class EndReason {
    @SerialName("host-ended")
    HOST_ENDED,

    @SerialName("expired")
    EXPIRED,
}

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("hello")
data class Hello(val protocol: Int, val app: JamApp, val appVersion: String) : ClientMessage

@Serializable
@SerialName("create")
data class Create(val id: String, val hostName: String, val settings: JamSettingsPatch? = null) :
    ClientMessage

@Serializable
@SerialName("resume")
data class Resume(
    val id: String,
    val roomId: String,
    val hostSecret: String,
    val snapshot: JsonElement = JsonNull,
    val outbox: List<OutboxEntry>,
) : ClientMessage

@Serializable
@SerialName("playing")
data class Playing(
    val source: NowPlayingSource,
    val itemId: String? = null,
    val track: JamTrack? = null,
    val positionMs: Long,
    val paused: Boolean,
) : ClientMessage

@Serializable
@SerialName("started")
data class Started(val itemId: String) : ClientMessage

@Serializable
@SerialName("add")
data class Add(val id: String, val trackId: String? = null, val track: JamTrack? = null) : ClientMessage

@Serializable
@SerialName("pin")
data class Pin(val id: String, val itemId: String) : ClientMessage

@Serializable
@SerialName("remove")
data class Remove(val id: String, val itemId: String) : ClientMessage

@Serializable
@SerialName("kick")
data class Kick(val id: String, val publicId: String) : ClientMessage

@Serializable
@SerialName("settings")
data class ChangeSettings(val id: String, val settings: JamSettingsPatch) : ClientMessage

@Serializable
@SerialName("rotateLink")
data class RotateLink(val id: String) : ClientMessage

@Serializable
@SerialName("end")
data class End(val id: String) : ClientMessage

@Serializable
@SerialName("searchResult")
data class SearchResult(
    val requestId: String,
    val tracks: List<JamTrack>? = null,
    val error: SearchError? = null,
) : ClientMessage

@Serializable
@SerialName("validateResult")
data class ValidateResult(val requestId: String, val results: List<ValidateEntry>) : ClientMessage

@Serializable
@SerialName("join")
data class Join(
    val id: String,
    val roomId: String,
    val joinSecret: String,
    val participantId: String,
    val name: String,
) : ClientMessage

@Serializable
@SerialName("search")
data class Search(val id: String, val text: String) : ClientMessage

@Serializable
@SerialName("skip")
data class Skip(val id: String, val itemId: String) : ClientMessage

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("welcome")
data class Welcome(val protocol: Int, val serverTime: Long) : ServerMessage

@Serializable
@SerialName("rejected")
data class Rejected(
    val id: String? = null,
    val reason: String,
    val detail: String? = null,
    val serverProtocol: Int? = null,
) : ServerMessage

@Serializable
@SerialName("ack")
data class Ack(val id: String) : ServerMessage

@Serializable
@SerialName("created")
data class Created(
    val id: String,
    val roomId: String,
    val hostSecret: String,
    val joinSecret: String,
    val joinUrl: String,
    val publicId: String,
) : ServerMessage

@Serializable
@SerialName("resumed")
data class Resumed(val id: String, val restored: Boolean) : ServerMessage

@Serializable
@SerialName("joined")
data class Joined(val id: String, val publicId: String) : ServerMessage

@Serializable
@SerialName("searchResults")
data class SearchResults(val id: String, val tracks: List<JamTrack>) : ServerMessage

@Serializable
@SerialName("linkRotated")
data class LinkRotated(val id: String, val joinSecret: String, val joinUrl: String) : ServerMessage

@Serializable
@SerialName("searchRequest")
data class SearchRequest(val requestId: String, val text: String) : ServerMessage

@Serializable
@SerialName("validateRequest")
data class ValidateRequest(val requestId: String, val trackIds: List<String>) : ServerMessage

@Serializable
@SerialName("command")
data class Command(val kind: CommandKind, val itemId: String) : ServerMessage

@Serializable
@SerialName("snapshot")
data class Snapshot(val data: JsonElement) : ServerMessage

@Serializable
@SerialName("state")
data class State(val version: Long, val serverTime: Long, val room: JamRoom) : ServerMessage

@Serializable
@SerialName("ended")
data class Ended(val reason: EndReason) : ServerMessage

@Serializable
@SerialName("kicked")
data object Kicked : ServerMessage
