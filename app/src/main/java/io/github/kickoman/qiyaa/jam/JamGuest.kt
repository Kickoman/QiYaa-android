package io.github.kickoman.qiyaa.jam

import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
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

sealed interface GuestPhase {
    val isActive: Boolean get() = this == Joining || this == InRoom || this == WaitingForRoom

    data object None : GuestPhase

    data object NoSecret : GuestPhase

    data object NeedName : GuestPhase

    data object Joining : GuestPhase

    data object InRoom : GuestPhase

    data object WaitingForRoom : GuestPhase

    data class Refused(val reason: String) : GuestPhase

    data class Ended(val reason: EndReason) : GuestPhase

    data object Kicked : GuestPhase

    data object UpdateRequired : GuestPhase
}

sealed interface GuestAdd {
    data object Sending : GuestAdd

    data object Added : GuestAdd

    data class Failed(val reason: String) : GuestAdd
}

enum class GuestAddResult { SENT, NOT_CONNECTED, HOST_OFFLINE, INVALID }

sealed interface AddAction {
    data object Yours : AddAction

    data class QueuedBy(val name: String?) : AddAction

    data object Sending : AddAction

    data object HostOffline : AddAction

    data class Limit(val max: Int) : AddAction

    data class Failed(val reason: String) : AddAction

    data object Add : AddAction
}

sealed interface JamGuestEvent {
    data class Refused(val reason: String) : JamGuestEvent
}

data class JamGuestState(
    val phase: GuestPhase = GuestPhase.None,
    val serverUrl: String? = null,
    val roomId: String? = null,
    val lastName: String? = null,
    val connection: JamStatus = JamStatus.IDLE,
    val connected: Boolean = false,
    val room: JamRoom? = null,
    val adds: Map<String, GuestAdd> = emptyMap(),
) {
    val waitingOfYours: Int
        get() {
            val room = room ?: return 0
            return room.queue.count { it.addedBy == room.you.publicId }
        }

    val canSkip: Boolean
        get() {
            val room = room ?: return false
            return connected &&
                room.hostOnline &&
                room.settings.guestsCanSkip &&
                room.nowPlaying.source == NowPlayingSource.ITEM &&
                room.nowPlaying.itemId != null
        }

    fun canRemove(item: JamQueueItem): Boolean = connected && item.addedBy == room?.you?.publicId

    fun addAction(trackId: String): AddAction? {
        val room = room ?: return null
        val waiting = room.queue.firstOrNull { it.track.id == trackId }
        if (waiting != null) {
            if (waiting.addedBy == room.you.publicId) return AddAction.Yours
            return AddAction.QueuedBy(room.participants.firstOrNull { it.publicId == waiting.addedBy }?.name)
        }
        val add = adds[trackId]
        if (add == GuestAdd.Sending) return AddAction.Sending
        if (!room.hostOnline) return AddAction.HostOffline
        val max = room.settings.maxPendingPerGuest
        if (waitingOfYours >= max) return AddAction.Limit(max)
        if (add is GuestAdd.Failed && add.reason !in QUEUE_SHOWS) return AddAction.Failed(add.reason)
        return AddAction.Add
    }

    private companion object {
        val QUEUE_SHOWS = setOf("duplicate", "queue-limit")
    }
}

fun JamNowPlaying.positionMsAt(serverNow: Long): Long {
    val durationMs = track?.durationMs ?: return 0
    val position = if (paused) positionMs else positionMs + (serverNow - reportedAt)
    return position.coerceIn(0, durationMs.coerceAtLeast(0))
}

object GuestNames {
    const val MAX_NAME = 24
    private val JAVASCRIPT_SPACES =
        Regex(
            "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+",
        )

    fun clean(name: String): String? {
        val cleaned = name.replace(JAVASCRIPT_SPACES, " ").trim(' ')
        return cleaned.takeIf { it.codePointCount(0, it.length) in 1..MAX_NAME }
    }

    fun prefill(name: String): String {
        val cleaned = name.replace(JAVASCRIPT_SPACES, " ").trim(' ')
        if (cleaned.codePointCount(0, cleaned.length) <= MAX_NAME) return cleaned
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_NAME)).trimEnd(' ')
    }
}

class JamGuest(
    transport: JamTransport,
    connectivity: Flow<Boolean>,
    private val store: JamGuestStore,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    appVersion: String,
    private val newParticipantId: () -> String = { UUID.randomUUID().toString() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val random: () -> Double = Math::random,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : JamHandler {
    private sealed interface Request {
        data object Join : Request

        data class Add(val trackId: String) : Request

        data object Change : Request
    }

    private val client = JamClient(transport, connectivity, scope, appVersion, this, clock) { log(it, null) }
    private val mutableState = MutableStateFlow(JamGuestState())
    val state: StateFlow<JamGuestState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<JamGuestEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<JamGuestEvent> = mutableEvents.asSharedFlow()

    private var memory: GuestMemory = store.load(clock())
    private var session: GuestRoom? = null
    private var linkSecret: String? = null
    private var phase: GuestPhase = GuestPhase.None
    private var connected = false
    private var joinId: String? = null
    private val requests = HashMap<String, Request>()
    private var adds: Map<String, GuestAdd> = emptyMap()
    private var room: JamRoom? = null
    private var lastVersion = -1L
    private var missingSince: Long? = null
    private var retries = 0
    private var roomWait: Job? = null
    private val writes = Channel<GuestMemory?>(Channel.CONFLATED)

    init {
        scope.launch {
            for (written in writes) {
                try {
                    withContext(io) { if (written == null) store.clear() else store.save(written) }
                } catch (failed: Exception) {
                    if (failed is CancellationException) throw failed
                    log("jam: could not store the guest's memberships", failed)
                }
            }
        }
        scope.launch { client.status.collect { status -> onStatus(status) } }
        publish()
    }

    fun serverNow(): Long = client.serverNow()

    fun open(link: JamLink) {
        val key = GuestRoom(link.serverUrl, link.roomId)
        if (key == session && (phase.isActive || phase == GuestPhase.NeedName)) return
        endSession()
        memory = memory.alive(clock())
        val known = memory.membership(key)
        session = key
        when {
            known != null -> {
                val secret = link.joinSecret ?: known.joinSecret
                remember(memory.with(known.copy(joinSecret = secret)).copy(current = key))
                connect()
            }

            link.joinSecret == null -> phase = GuestPhase.NoSecret

            else -> {
                linkSecret = link.joinSecret
                phase = GuestPhase.NeedName
            }
        }
        publish()
    }

    fun join(name: String): Boolean {
        val key = session ?: return false
        val secret = linkSecret ?: return false
        if (phase != GuestPhase.NeedName) return false
        val cleaned = GuestNames.clean(name) ?: return false
        val participantId = newParticipantId()
        if (!passes(Join(PROBE_ID, key.roomId, secret, participantId, cleaned))) return false
        val membership = GuestMembership(key, participantId, cleaned, secret, clock())
        remember(memory.with(membership).copy(current = key, lastName = cleaned))
        linkSecret = null
        connect()
        publish()
        return true
    }

    fun restore(): Boolean {
        if (phase != GuestPhase.None) return true
        memory = memory.alive(clock())
        val key = memory.current ?: return false
        session = key
        connect()
        publish()
        return true
    }

    fun add(track: JamTrack): GuestAddResult {
        val current = room
        if (!connected || phase != GuestPhase.InRoom || current == null) return GuestAddResult.NOT_CONNECTED
        if (!current.hostOnline) return GuestAddResult.HOST_OFFLINE
        val id = newId()
        val message = Add(id, track = track)
        if (!track.isCatalog || !passes(message) || !fitsGuestFrame(message)) return GuestAddResult.INVALID
        if (!client.send(message)) return GuestAddResult.NOT_CONNECTED
        requests[id] = Request.Add(track.id)
        adds = adds + (track.id to GuestAdd.Sending)
        publish()
        return GuestAddResult.SENT
    }

    fun remove(itemId: String): Boolean {
        val state = mutableState.value
        val item = room?.queue?.firstOrNull { it.itemId == itemId } ?: return false
        if (!state.canRemove(item)) return false
        return change(newId()) { Remove(it, itemId) }
    }

    fun skip(itemId: String): Boolean {
        if (!mutableState.value.canSkip || room?.nowPlaying?.itemId != itemId) return false
        return change(newId()) { Skip(it, itemId) }
    }

    fun leave() {
        endSession()
        publish()
    }

    fun forgetAll() {
        endSession()
        memory = GuestMemory()
        writes.trySend(null)
        publish()
    }

    override fun onWelcome() {
        cancelRoomWait()
        connected = false
        lastVersion = -1
        joinId = null
        requests.clear()
        adds = adds.filterValues { it != GuestAdd.Sending }
        sendJoin()
        publish()
    }

    override fun onMessage(message: ServerMessage) {
        if (session == null) return
        when (message) {
            is Joined -> if (message.id == joinId) joined(message.id)
            is State -> applyState(message)
            is Ack -> acked(message.id)
            is Rejected -> rejected(message)
            is Ended -> ended(message.reason)
            Kicked -> finish(GuestPhase.Kicked)
            else -> Unit
        }
        publish()
    }

    private fun connect() {
        phase = GuestPhase.Joining
        connected = false
        missingSince = null
        retries = 0
        val key = session ?: return
        client.start(JamClient.socketUrl(key.serverUrl))
    }

    private fun sendJoin() {
        val key = session ?: return
        val membership = memory.membership(key) ?: return
        val id = newId()
        val join = Join(id, key.roomId, membership.joinSecret, membership.participantId, membership.name)
        if (!client.send(join)) return
        joinId = id
        requests[id] = Request.Join
    }

    private fun joined(id: String) {
        requests.remove(id)
        joinId = null
        connected = true
        phase = GuestPhase.InRoom
        cancelRoomWait()
        missingSince = null
        retries = 0
        val membership = session?.let(memory::membership) ?: return
        if (!membership.joined) remember(memory.with(membership.copy(joined = true)))
    }

    private fun applyState(message: State) {
        if (!connected) return
        if (lastVersion >= 0 && message.version <= lastVersion) return
        lastVersion = message.version
        room = message.room
    }

    private fun acked(id: String) {
        val request = requests.remove(id)
        if (request is Request.Add) adds = adds + (request.trackId to GuestAdd.Added)
    }

    private fun rejected(message: Rejected) {
        val id = message.id
        val request = id?.let(requests::remove)
        when {
            message.reason == UPDATE_REQUIRED -> refusedForGood(GuestPhase.UpdateRequired)
            request is Request.Add -> adds = adds + (request.trackId to GuestAdd.Failed(message.reason))
            request == Request.Join -> if (id == joinId) joinRefused(id, message.reason)
            else -> mutableEvents.tryEmit(JamGuestEvent.Refused(message.reason))
        }
    }

    private fun joinRefused(id: String, reason: String) {
        joinId = null
        val membership = session?.let(memory::membership)
        when {
            reason == NOT_ALLOWED -> {
                log("jam: a join was refused as not-allowed: this connection has joined already", null)
                joined(id)
            }

            membership == null || !membership.joined ->
                refusedForGood(if (reason == KICKED) GuestPhase.Kicked else GuestPhase.Refused(reason))

            reason == KICKED -> finish(GuestPhase.Kicked)

            membership.ended && reason == ROOM_NOT_FOUND -> finish(GuestPhase.Ended(EndReason.HOST_ENDED))

            !membership.ended && reason in ROOM_WAIT_REASONS && waitForRoom() -> Unit

            else -> finish(GuestPhase.Refused(reason))
        }
    }

    private fun waitForRoom(): Boolean {
        val now = clock()
        val since = missingSince ?: now
        if (now - since >= ROOM_WAIT_MS) return false
        missingSince = since
        val base = ROOM_RETRY_MS[retries.coerceAtMost(ROOM_RETRY_MS.lastIndex)]
        val delayMs = (base * (JITTER_FLOOR + JITTER_SPAN * random())).roundToLong()
        retries++
        phase = GuestPhase.WaitingForRoom
        cancelRoomWait()
        roomWait =
            scope.launch {
                delay(delayMs)
                roomWait = null
                if (phase == GuestPhase.WaitingForRoom && joinId == null) sendJoin()
            }
        return true
    }

    private fun ended(reason: EndReason) {
        val membership = session?.let(memory::membership)
        if (reason == EndReason.HOST_ENDED &&
            membership != null
        ) {
            remember(memory.with(membership.copy(ended = true)))
        }
        finish(GuestPhase.Ended(reason))
    }

    private fun refusedForGood(final: GuestPhase) {
        val key = session
        val membership = key?.let(memory::membership)
        if (key != null && membership != null && !membership.joined) remember(memory.without(key))
        finish(final)
    }

    private fun finish(final: GuestPhase) {
        phase = final
        disconnect()
        if (memory.current != null) remember(memory.copy(current = null))
    }

    private fun endSession() {
        disconnect()
        session = null
        linkSecret = null
        room = null
        adds = emptyMap()
        phase = GuestPhase.None
        if (memory.current != null) remember(memory.copy(current = null))
    }

    private fun disconnect() {
        cancelRoomWait()
        connected = false
        joinId = null
        requests.clear()
        adds = adds.filterValues { it != GuestAdd.Sending }
        if (client.status.value != JamStatus.IDLE) client.stop()
    }

    private fun cancelRoomWait() {
        roomWait?.cancel()
        roomWait = null
    }

    private fun change(id: String, build: (String) -> ClientMessage): Boolean {
        if (!connected) return false
        val message = build(id)
        if (!passes(message) || !client.send(message)) return false
        requests[id] = Request.Change
        return true
    }

    private fun passes(message: ClientMessage): Boolean =
        JamCodec.decodeClient(JamCodec.encode(message)) is Decoded.Message

    private fun fitsGuestFrame(message: ClientMessage): Boolean =
        JamCodec.encode(message).toByteArray(Charsets.UTF_8).size <= GUEST_FRAME_BYTES

    private fun remember(updated: GuestMemory) {
        memory = updated
        writes.trySend(updated)
    }

    private fun onStatus(status: JamStatus) {
        if (status != JamStatus.ONLINE) connected = false
        publish()
    }

    private fun publish() {
        val key = session
        mutableState.value =
            JamGuestState(
                phase = phase,
                serverUrl = key?.serverUrl,
                roomId = key?.roomId,
                lastName = memory.lastName,
                connection = client.status.value,
                connected = connected,
                room = if (key != null) room else null,
                adds = adds,
            )
    }

    companion object {
        const val GUEST_FRAME_BYTES = 4_096
        const val ROOM_WAIT_MS = 10 * 60 * 1_000L
        val ROOM_RETRY_MS = listOf(5_000L, 10_000L, 20_000L, 30_000L)
        private const val JITTER_FLOOR = 0.8
        private const val JITTER_SPAN = 0.4
        private const val EVENT_BUFFER = 8
        private const val PROBE_ID = "probe"
        private const val ROOM_NOT_FOUND = "room-not-found"
        private const val KICKED = "kicked"
        private const val NOT_ALLOWED = "not-allowed"
        private const val UPDATE_REQUIRED = "update-required"
        private val ROOM_WAIT_REASONS = setOf(ROOM_NOT_FOUND, "rate-limited")
    }
}
