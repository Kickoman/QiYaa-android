package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.jam.Ack
import io.github.kickoman.qiyaa.jam.Add
import io.github.kickoman.qiyaa.jam.ChangeSettings
import io.github.kickoman.qiyaa.jam.ClientMessage
import io.github.kickoman.qiyaa.jam.Command
import io.github.kickoman.qiyaa.jam.CommandKind
import io.github.kickoman.qiyaa.jam.Create
import io.github.kickoman.qiyaa.jam.Created
import io.github.kickoman.qiyaa.jam.Decoded
import io.github.kickoman.qiyaa.jam.End
import io.github.kickoman.qiyaa.jam.EndReason
import io.github.kickoman.qiyaa.jam.Ended
import io.github.kickoman.qiyaa.jam.JamClient
import io.github.kickoman.qiyaa.jam.JamCodec
import io.github.kickoman.qiyaa.jam.JamHandler
import io.github.kickoman.qiyaa.jam.JamRoom
import io.github.kickoman.qiyaa.jam.JamSession
import io.github.kickoman.qiyaa.jam.JamSessionStore
import io.github.kickoman.qiyaa.jam.JamSettingsPatch
import io.github.kickoman.qiyaa.jam.JamStatus
import io.github.kickoman.qiyaa.jam.JamTransport
import io.github.kickoman.qiyaa.jam.Kick
import io.github.kickoman.qiyaa.jam.LinkRotated
import io.github.kickoman.qiyaa.jam.NowPlayingSource
import io.github.kickoman.qiyaa.jam.OutboxEntry
import io.github.kickoman.qiyaa.jam.Pin
import io.github.kickoman.qiyaa.jam.Playing
import io.github.kickoman.qiyaa.jam.Rejected
import io.github.kickoman.qiyaa.jam.Remove
import io.github.kickoman.qiyaa.jam.Resume
import io.github.kickoman.qiyaa.jam.Resumed
import io.github.kickoman.qiyaa.jam.RotateLink
import io.github.kickoman.qiyaa.jam.SearchError
import io.github.kickoman.qiyaa.jam.SearchRequest
import io.github.kickoman.qiyaa.jam.SearchResult
import io.github.kickoman.qiyaa.jam.ServerMessage
import io.github.kickoman.qiyaa.jam.Snapshot
import io.github.kickoman.qiyaa.jam.State
import io.github.kickoman.qiyaa.jam.ValidateEntry
import io.github.kickoman.qiyaa.jam.ValidateReason
import io.github.kickoman.qiyaa.jam.ValidateRequest
import io.github.kickoman.qiyaa.jam.ValidateResult
import io.github.kickoman.qiyaa.queue.JamEntry
import io.github.kickoman.qiyaa.queue.JamPlayback
import io.github.kickoman.qiyaa.queue.JamPlaybackListener
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.Track
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The Yandex requests a jam host runs for its guests (HOST-28, HOST-30). */
interface JamCatalog {
    suspend fun searchTracks(text: String): List<Track>

    suspend fun tracks(ids: List<String>): List<Track>
}

data class JamHostConfig(val serverUrl: String, val waveFeedback: Boolean)

enum class JamHostPhase { NONE, CREATING, ACTIVE }

data class JamHostState(
    val phase: JamHostPhase = JamHostPhase.NONE,
    val connection: JamStatus = JamStatus.IDLE,
    /** The server accepted this connection's `create` or `resume`: the host's controls work. */
    val connected: Boolean = false,
    val room: JamRoom? = null,
    val joinUrl: String? = null,
    /** A jam from before the process was killed waits for "Continue the jam?" (HOST-23). */
    val storedSession: Boolean = false,
)

sealed interface JamHostEvent {
    /** The server refused a request of the host; `reason` as in the protocol, unknown ones included. */
    data class Refused(val reason: String) : JamHostEvent

    data class Ended(val why: Why) : JamHostEvent {
        enum class Why { BY_HOST, BY_SERVER, EXPIRED, GONE }
    }
}

/**
 * The host's side of a jam: `JamClient` to the server, the queue's jam mode to the player, and
 * `JamCatalog` for the guests' search and checks. Scenarios: `spec/jam/host.md`.
 */
class JamHost(
    transport: JamTransport,
    connectivity: Flow<Boolean>,
    private val queue: QueueController,
    private val catalog: JamCatalog,
    private val store: JamSessionStore,
    private val config: () -> JamHostConfig,
    private val queueTitle: () -> String,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    appVersion: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : JamHandler,
    JamPlaybackListener {
    private data class CreateRequest(val hostName: String, val settings: JamSettingsPatch?)

    private val client = JamClient(transport, connectivity, scope, appVersion, this, clock) { log(it, null) }
    private val mutableState = MutableStateFlow(JamHostState())
    val state: StateFlow<JamHostState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<JamHostEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<JamHostEvent> = mutableEvents.asSharedFlow()

    private var session: JamSession? = null
    private var stored: JamSession? = null
    private var discarding: JamSession? = null
    private var creating: CreateRequest? = null
    private var connected = false
    private var requestId: String? = null
    private var endId: String? = null
    private var lastVersion = -1L
    private var pendingPin: String? = null
    private var pendingPinAdd: String? = null
    private var serverUrl = ""
    private var retry: Job? = null
    private val writes = Channel<JamSession?>(Channel.CONFLATED)

    init {
        stored = store.load()
        scope.launch {
            for (session in writes) {
                try {
                    withContext(io) { if (session == null) store.clear() else store.save(session) }
                } catch (failed: Exception) {
                    if (failed is CancellationException) throw failed
                    log("jam: could not store the session", failed)
                }
            }
        }
        scope.launch { client.status.collect { status -> onStatus(status) } }
        scope.launch { client.outbox.collect { outbox -> session?.let { save(it.copy(outbox = outbox)) } } }
        publish()
    }

    /**
     * Starts a new jam; false when one is on, the server is not set, or the name or the settings do
     * not pass the protocol (the server would close the connection, and the retry would send the same
     * again).
     */
    fun create(hostName: String, settings: JamSettingsPatch? = null): Boolean {
        if (session != null || creating != null) return false
        val name = hostName.trim().take(MAX_NAME).trim()
        val server = config()
        if (server.serverUrl.isBlank()) return false
        val probe = Create("probe", name, settings)
        if (JamCodec.decodeClient(JamCodec.encode(probe)) !is Decoded.Message) return false
        stopDiscarding()
        creating = CreateRequest(name, settings)
        connect(server.serverUrl)
        publish()
        return true
    }

    /** Stops waiting for `created`, for example when the server cannot be reached. */
    fun cancelCreate() {
        if (creating == null) return
        creating = null
        disconnect()
        publish()
    }

    /** "Continue the jam?" — yes (HOST-23). */
    fun continueStored() {
        val resumed = stored ?: return
        stored = null
        session = resumed
        client.restoreOutbox(resumed.outbox)
        queue.startJam(queueTitle(), this, config().waveFeedback)
        connect(config().serverUrl)
        publish()
    }

    /** "Continue the jam?" — no: the room is resumed only to be ended (HOST-23). */
    fun discardStored() {
        val old = stored ?: return
        stored = null
        write(null)
        queue.endJam()
        val server = config()
        if (server.serverUrl.isNotBlank()) {
            discarding = old
            connect(server.serverUrl)
        }
        publish()
    }

    /** The user ends the jam (HOST-32). */
    fun end() {
        if (session == null) return
        if (connected) client.send(End(newId()))
        endLocally(JamHostEvent.Ended.Why.BY_HOST)
    }

    /** HOST-20: the track reaches the queue with the next state, never directly. */
    fun add(track: Track): Boolean {
        val jamTrack = JamTracks.toJam(track) ?: return false
        return request { Add(it, track = jamTrack) }
    }

    /** "Play next": `pin` for a track already waiting, otherwise `add`, then `pin` once it is there. */
    fun playNext(track: Track): Boolean {
        val waiting = mutableState.value.room?.queue?.firstOrNull { it.track.id == track.id }
        if (waiting != null) return pin(waiting.itemId)
        val jamTrack = JamTracks.toJam(track) ?: return false
        val id = newId()
        if (!connected || !client.send(Add(id, track = jamTrack))) return false
        pendingPin = track.id
        pendingPinAdd = id
        return true
    }

    fun pin(itemId: String): Boolean = request { Pin(it, itemId) }

    fun remove(itemId: String): Boolean = request { Remove(it, itemId) }

    fun kick(publicId: String): Boolean = request { Kick(it, publicId) }

    fun changeSettings(settings: JamSettingsPatch): Boolean = request { ChangeSettings(it, settings) }

    fun rotateLink(): Boolean = request { RotateLink(it) }

    /** The connection lives with `PlaybackService`: a service that comes back resumes the jam. */
    fun onServiceStarted() {
        if (session == null || client.status.value !in setOf(JamStatus.IDLE, JamStatus.STOPPED)) return
        connect(config().serverUrl)
    }

    /** The system unloaded the service: the room waits for the host (REC-05), the jam stays stored. */
    fun onServiceStopped() {
        if (session == null) return
        disconnect()
        publish()
    }

    override fun onWelcome() {
        connected = false
        lastVersion = -1
        val active = session
        val old = discarding
        val create = creating
        when {
            active != null -> sendResume(active, client.outbox.value)
            old != null -> sendResume(old, old.outbox)
            create != null -> {
                val id = newId()
                requestId = id
                client.send(Create(id, create.hostName, create.settings))
            }
        }
        publish()
    }

    override fun onMessage(message: ServerMessage) {
        when (message) {
            is Created -> if (message.id == requestId) created(message)
            is Resumed -> if (message.id == requestId) resumed()
            is Rejected -> rejected(message)
            is Ack -> if (message.id == endId) finishDiscard()
            is State -> applyState(message)
            is Snapshot -> session?.let { save(it.copy(snapshot = message.data as? JsonObject)) }
            is LinkRotated -> session?.let { save(it.copy(joinUrl = message.joinUrl)) }
            is SearchRequest -> search(message)
            is ValidateRequest -> validate(message)
            is Command -> if (message.kind == CommandKind.SKIP &&
                session != null
            ) {
                queue.jamSkip(message.itemId)
            }
            is Ended -> ended(message.reason)
            else -> Unit
        }
        publish()
    }

    override fun onItemStarted(itemId: String) {
        client.started(itemId, sendNow = connected)
    }

    override fun onPlayback(playback: JamPlayback) {
        if (!connected) return // HOST-25: playing is not queued
        val message =
            when (playback.kind) {
                JamPlayback.Kind.ITEM -> Playing(
                    NowPlayingSource.ITEM,
                    itemId = playback.itemId,
                    positionMs = playback.positionMs,
                    paused = playback.paused,
                )
                JamPlayback.Kind.WAVE -> Playing(
                    NowPlayingSource.WAVE,
                    // A wave track the protocol cannot carry is not reported at all.
                    track = playback.track?.let(JamTracks::toJam) ?: return,
                    positionMs = playback.positionMs,
                    paused = playback.paused,
                )
                JamPlayback.Kind.IDLE -> Playing(NowPlayingSource.IDLE, positionMs = 0, paused = true)
            }
        client.send(message)
    }

    private fun request(build: (String) -> ClientMessage): Boolean = connected && client.send(build(newId()))

    private fun sendResume(resumed: JamSession, outbox: List<String>) {
        val id = newId()
        requestId = id
        client.send(
            Resume(
                id = id,
                roomId = resumed.roomId,
                hostSecret = resumed.hostSecret,
                snapshot = resumed.snapshot ?: JsonNull,
                outbox = outbox.takeLast(MAX_OUTBOX).map { OutboxEntry(itemId = it) },
            ),
        )
    }

    private fun created(message: Created) {
        creating = null
        requestId = null
        connected = true
        save(JamSession(message.roomId, message.hostSecret, message.joinUrl, null, emptyList()))
        queue.startJam(queueTitle(), this, config().waveFeedback)
    }

    private fun resumed() {
        requestId = null
        if (discarding != null) {
            val id = newId()
            endId = id
            client.send(End(id))
            return
        }
        connected = true
        client.clearOutbox()
        queue.reportJamPlayback()
    }

    private fun rejected(message: Rejected) {
        val id = message.id
        when {
            id != null && id == pendingPinAdd -> {
                pendingPin = null
                pendingPinAdd = null
                mutableEvents.tryEmit(JamHostEvent.Refused(message.reason))
            }
            id != null && id == requestId && discarding != null -> finishDiscard()
            id != null && id == endId -> finishDiscard()
            id != null && id == requestId && creating != null -> {
                creating = null
                requestId = null
                disconnect()
                mutableEvents.tryEmit(JamHostEvent.Refused(message.reason))
            }
            id != null && id == requestId && session != null -> {
                requestId = null
                if (message.reason in GONE_REASONS) {
                    endLocally(JamHostEvent.Ended.Why.GONE) // HOST-24
                } else {
                    mutableEvents.tryEmit(JamHostEvent.Refused(message.reason))
                    retryLater()
                }
            }
            else -> mutableEvents.tryEmit(JamHostEvent.Refused(message.reason))
        }
    }

    private fun applyState(message: State) {
        if (session == null || !connected) return
        if (lastVersion >= 0 && message.version <= lastVersion) return
        lastVersion = message.version
        val room = message.room
        mutableState.value = mutableState.value.copy(room = room)
        queue.onJamQueue(
            room.queue.map { JamEntry(it.itemId, JamTracks.fromJam(it.track), it.addedBy) },
            room.fallback.seeds,
            room.fallback.seedsVersion,
        )
        val trackId = pendingPin ?: return
        val item =
            room.queue.firstOrNull { it.track.id == trackId && it.addedBy == room.you.publicId } ?: return
        pendingPin = null
        pendingPinAdd = null
        if (!item.pinned) pin(item.itemId)
    }

    private fun search(message: SearchRequest) {
        scope.launch {
            val reply =
                try {
                    val found = withContext(io) { catalog.searchTracks(message.text) }
                    val tracks = found.filter {
                        it.available
                    }.mapNotNull(JamTracks::toJam).take(SEARCH_RESULTS)
                    SearchResult(message.requestId, tracks = tracks)
                } catch (failed: Exception) {
                    if (failed is CancellationException) throw failed
                    log("jam: a guest's search failed", failed)
                    val unauthorized = ErrorKind.of(failed) == ErrorKind.TokenRejected
                    SearchResult(
                        message.requestId,
                        error = if (unauthorized) SearchError.UNAUTHORIZED else SearchError.FAILED,
                    )
                }
            if (connected) client.send(reply)
        }
    }

    private fun validate(message: ValidateRequest) {
        scope.launch {
            val results =
                try {
                    val found = withContext(io) { catalog.tracks(message.trackIds) }.associateBy { it.id }
                    message.trackIds.map { id ->
                        val track = found[id]?.takeIf { it.available }?.let(JamTracks::toJam)
                        if (track != null) {
                            ValidateEntry(id, track = track)
                        } else {
                            ValidateEntry(id, reason = ValidateReason.TRACK_UNAVAILABLE)
                        }
                    }
                } catch (failed: Exception) {
                    if (failed is CancellationException) throw failed
                    log("jam: a guest's track check failed", failed)
                    message.trackIds.map { ValidateEntry(it, reason = ValidateReason.FAILED) }
                }
            if (connected) client.send(ValidateResult(message.requestId, results))
        }
    }

    private fun ended(reason: EndReason) {
        if (discarding != null) {
            finishDiscard()
            return
        }
        if (session == null) return
        endLocally(
            if (reason ==
                EndReason.EXPIRED
            ) {
                JamHostEvent.Ended.Why.EXPIRED
            } else {
                JamHostEvent.Ended.Why.BY_SERVER
            },
        )
    }

    private fun endLocally(why: JamHostEvent.Ended.Why) {
        session = null
        pendingPin = null
        pendingPinAdd = null
        requestId = null
        disconnect()
        client.clearOutbox()
        write(null)
        queue.endJam()
        mutableState.value = mutableState.value.copy(room = null)
        mutableEvents.tryEmit(JamHostEvent.Ended(why))
        publish()
    }

    private fun finishDiscard() {
        discarding = null
        endId = null
        requestId = null
        if (session == null && creating == null) disconnect()
    }

    private fun stopDiscarding() {
        if (discarding == null) return
        finishDiscard()
    }

    private fun connect(url: String) {
        retry?.cancel()
        if (url.isBlank()) return // no server set: the jam plays on without one
        serverUrl = url
        client.start(JamClient.socketUrl(url))
    }

    private fun disconnect() {
        retry?.cancel()
        connected = false
        client.stop()
    }

    private fun retryLater() {
        disconnect()
        retry =
            scope.launch {
                delay(RESUME_RETRY_MS)
                if (session != null) connect(serverUrl)
            }
    }

    private fun onStatus(status: JamStatus) {
        if (status != JamStatus.ONLINE) connected = false
        publish()
    }

    private fun save(updated: JamSession) {
        session = updated
        write(updated)
        publish()
    }

    private fun write(updated: JamSession?) {
        writes.trySend(updated)
    }

    private fun publish() {
        val active = session
        mutableState.value =
            mutableState.value.copy(
                phase =
                when {
                    active != null -> JamHostPhase.ACTIVE
                    creating != null -> JamHostPhase.CREATING
                    else -> JamHostPhase.NONE
                },
                connection = client.status.value,
                connected = connected,
                room = if (active != null) mutableState.value.room else null,
                joinUrl = active?.joinUrl,
                storedSession = stored != null,
            )
    }

    companion object {
        const val MAX_NAME = 24
        const val MAX_OUTBOX = 500
        const val SEARCH_RESULTS = 20
        const val RESUME_RETRY_MS = 30_000L
        private const val EVENT_BUFFER = 8
        val GONE_REASONS = setOf("room-not-found", "bad-secret")
    }
}
