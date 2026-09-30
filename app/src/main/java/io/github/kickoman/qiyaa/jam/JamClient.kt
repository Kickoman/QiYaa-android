package io.github.kickoman.qiyaa.jam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

interface JamSocket {
    fun send(text: String): Boolean

    fun close(code: Int)
}

interface JamSocketListener {
    fun onOpen()

    fun onMessage(text: String)

    fun onClosed(code: Int)
}

fun interface JamTransport {
    fun open(url: String, listener: JamSocketListener): JamSocket
}

enum class JamStatus { IDLE, CONNECTING, ONLINE, OFFLINE, STOPPED }

interface JamHandler {
    fun onWelcome()

    fun onMessage(message: ServerMessage)
}

class JamClient(
    private val transport: JamTransport,
    private val connectivity: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val handler: JamHandler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private class Connection {
        var socket: JamSocket? = null
    }

    private sealed interface Event {
        val connection: Connection

        class Opened(override val connection: Connection) : Event

        class Received(override val connection: Connection, val text: String) : Event

        class Closed(override val connection: Connection) : Event
    }

    private val mutableStatus = MutableStateFlow(JamStatus.IDLE)
    val status: StateFlow<JamStatus> = mutableStatus.asStateFlow()

    private val mutableOutbox = MutableStateFlow<List<String>>(emptyList())
    val outbox: StateFlow<List<String>> = mutableOutbox.asStateFlow()

    var clockOffsetMs: Long = 0
        private set

    private val events = Channel<Event>(Channel.UNLIMITED)
    private var url: String = ""
    private var current: Connection? = null
    private var attempt = 0
    private var retry: Job? = null
    private var consumer: Job? = null
    private var watcher: Job? = null

    fun serverNow(): Long = clock() + clockOffsetMs

    fun start(url: String) {
        this.url = url
        attempt = 0
        if (consumer == null) {
            consumer = scope.launch { for (event in events) handle(event) }
            watcher =
                scope.launch {
                    connectivity.distinctUntilChanged().collect { available -> if (available) networkBack() }
                }
        }
        connect()
    }

    fun stop() {
        retry?.cancel()
        retry = null
        current?.socket?.close(NORMAL_CLOSE)
        current = null
        mutableStatus.value = JamStatus.STOPPED
    }

    fun send(message: ClientMessage): Boolean {
        val open = current?.socket ?: return false
        if (mutableStatus.value != JamStatus.ONLINE) return false
        return open.send(JamCodec.encode(message))
    }

    /** `sendNow = false` while the server has not accepted the connection's `create` or `resume` yet. */
    fun started(itemId: String, sendNow: Boolean = true) {
        if (!sendNow || !send(Started(itemId))) mutableOutbox.value = mutableOutbox.value + itemId
    }

    fun restoreOutbox(itemIds: List<String>) {
        mutableOutbox.value = itemIds
    }

    fun clearOutbox() {
        mutableOutbox.value = emptyList()
    }

    private fun networkBack() {
        if (mutableStatus.value != JamStatus.OFFLINE) return
        retry?.cancel()
        attempt = 0
        connect()
    }

    private fun connect() {
        current?.socket?.close(NORMAL_CLOSE)
        mutableStatus.value = JamStatus.CONNECTING
        val connection = Connection()
        current = connection
        connection.socket =
            transport.open(
                url,
                object : JamSocketListener {
                    override fun onOpen() {
                        events.trySend(Event.Opened(connection))
                    }

                    override fun onMessage(text: String) {
                        events.trySend(Event.Received(connection, text))
                    }

                    override fun onClosed(code: Int) {
                        events.trySend(Event.Closed(connection))
                    }
                },
            )
    }

    private fun handle(event: Event) {
        if (event.connection !== current) return
        when (event) {
            is Event.Opened ->
                event.connection.socket?.send(
                    JamCodec.encode(Hello(JAM_PROTOCOL, JamApp.ANDROID, appVersion)),
                )
            is Event.Received -> receive(event.text)
            is Event.Closed -> closed()
        }
    }

    private fun receive(text: String) {
        val decoded = JamCodec.decodeServer(text)
        val message =
            when {
                decoded is Decoded.Message -> decoded.message
                JamCodec.isUnknownReason(
                    decoded,
                ) -> JamCodec.json.decodeFromString(ServerMessage.serializer(), text)
                else -> {
                    if (decoded is Decoded.Invalid) log("jam: ignored an invalid message: ${decoded.problem}")
                    return
                }
            }
        when (message) {
            is Welcome -> {
                clockOffsetMs = message.serverTime - clock()
                attempt = 0
                mutableStatus.value = JamStatus.ONLINE
                handler.onWelcome()
                return
            }
            is State -> clockOffsetMs = message.serverTime - clock()
            else -> Unit
        }
        handler.onMessage(message)
        if (endsTheJam(message)) stop()
    }

    private fun closed() {
        current = null
        if (mutableStatus.value == JamStatus.STOPPED) return
        mutableStatus.value = JamStatus.OFFLINE
        val delayMs = RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        attempt++
        retry?.cancel()
        retry =
            scope.launch {
                delay(delayMs)
                connect()
            }
    }

    private fun endsTheJam(message: ServerMessage): Boolean =
        message is Ended || message is Kicked || (message is Rejected && message.reason == "update-required")

    companion object {
        const val NORMAL_CLOSE = 1000
        val RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        /** `https://jam.example.org` → `wss://jam.example.org/ws`. */
        fun socketUrl(serverUrl: String): String {
            val trimmed = serverUrl.trim().trimEnd('/')
            val base =
                when {
                    trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                    trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                    else -> "wss://$trimmed"
                }
            return "$base/ws"
        }
    }
}
