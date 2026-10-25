package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AccessPoint
import io.github.kickoman.qiyaa.spotify.protocol.ApEvent
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.Ids
import io.github.kickoman.qiyaa.spotify.protocol.LoginError
import io.github.kickoman.qiyaa.spotify.protocol.ServerKey
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface SpotifyState {
    data object LoggedOut : SpotifyState

    data object Connecting : SpotifyState

    data class Online(val username: String, val country: String = "", val product: String = "") :
        SpotifyState {
        override fun toString(): String =
            "Online(username=<${username.length} chars>, country=$country, product=$product)"
    }

    data object Offline : SpotifyState

    data object Expired : SpotifyState

    data object PremiumRequired : SpotifyState

    data class Refused(val code: Int) : SpotifyState
}

interface SessionListener {
    fun loggedIn(reusable: Credentials)

    fun refused(error: LoginError)
}

data class SessionTiming(
    val keyTimeoutMs: Long = 5_000,
    val keyRetryMs: Long = 1_000,
    val loginWaitMs: Long = 20_000,
    val reconnectMs: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 32_000, 60_000),
    val connectTimeoutMs: Int = 10_000,
    val handshakeTimeoutMs: Long = 20_000,
) {
    init {
        require(reconnectMs.isNotEmpty()) { "at least one reconnect delay, got $reconnectMs" }
    }

    fun reconnectAfterMs(attempt: Int): Long = reconnectMs[attempt.coerceIn(0, reconnectMs.size - 1)]
}

class SpotifySession(
    client: OkHttpClient,
    private val identity: SpotifyIdentity,
    private val network: Flow<Boolean>,
    private val inUse: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val endpoints: Endpoints = Endpoints(),
    private val timing: SessionTiming = SessionTiming(),
    private val serverKey: ServerKey = ServerKey.SPOTIFY,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private val resolveClient =
        client.newBuilder().callTimeout(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    private val accessPointThread = Dispatchers.IO.limitedParallelism(1)
    private val random = SecureRandom()

    private val lock = Any()
    private var generation = 0
    private var listener: SessionListener? = null
    private var loop: Job? = null
    private var refusal: LoginError? = null
    private val keys = ArrayList<KeyRequest>()

    private var link: Link? = null

    private val mutableState = MutableStateFlow<SpotifyState>(SpotifyState.LoggedOut)
    val state: StateFlow<SpotifyState> = mutableState.asStateFlow()

    private val mutableProblems = MutableSharedFlow<SpotifyProblem>(extraBufferCapacity = PROBLEM_BUFFER)
    val problems: SharedFlow<SpotifyProblem> = mutableProblems.asSharedFlow()

    private val mutableKeysWaiting = MutableStateFlow(0)
    internal val keysWaiting: StateFlow<Int> = mutableKeysWaiting.asStateFlow()

    private val wanted: Flow<Boolean> = combine(inUse, keysWaiting) { used, waiting -> used || waiting > 0 }

    @Volatile
    var credentials: Credentials? = null
        private set

    @Volatile
    var country: String = ""
        private set

    @Volatile
    var spclients: List<String> = emptyList()
        private set

    fun login(credentials: Credentials, listener: SessionListener? = null) {
        val dropped =
            synchronized(lock) {
                loop?.cancel()
                val current = ++generation
                this.listener = listener
                this.credentials = credentials
                refusal = null
                country = ""
                mutableState.value = SpotifyState.Connecting
                loop = scope.launch(accessPointThread) { keepConnected(current) }
                takeKeys()
            }
        fail(dropped, SpotifyNetworkException("an audio key", SocketException("the Spotify login changed")))
    }

    fun logout() {
        val (stopped, dropped) =
            synchronized(lock) {
                generation++
                listener = null
                credentials = null
                refusal = null
                country = ""
                mutableState.value = SpotifyState.LoggedOut
                (loop.also { loop = null }) to takeKeys()
            }
        stopped?.cancel()
        fail(dropped, NotLoggedInException())
    }

    fun loggedInUsername(): String = synchronized(lock) {
        checkLoggedIn()
        credentials?.username ?: throw NotLoggedInException()
    }

    suspend fun audioKey(track: Bytes, file: Bytes): Bytes {
        require(track.size == Ids.GID_SIZE) { "a track gid is ${Ids.GID_SIZE} bytes, got ${track.size}" }
        require(file.size == Ids.FILE_ID_SIZE) { "a file id is ${Ids.FILE_ID_SIZE} bytes, got ${file.size}" }
        var last: KeyAnswer = KeyAnswer.Silent
        for (attempt in 0 until KEY_ATTEMPTS) {
            if (attempt > 0) delay(timing.keyRetryMs)
            last = ask(track, file)
            when (val answer = last) {
                is KeyAnswer.Key -> return answer.key

                is KeyAnswer.Refused -> if (answer.code == KEY_REFUSED_FOR_ACCOUNT) {
                    log("the access point refused an audio key: code ${answer.code}")
                    mutableProblems.tryEmit(SpotifyProblem.KeyRefused)
                    throw KeyRefusedException()
                }

                KeyAnswer.Silent -> Unit
            }
        }
        val detail =
            when (val answer = last) {
                is KeyAnswer.Refused -> "AesKeyError code ${answer.code}"
                else -> "no answer in ${timing.keyTimeoutMs} ms"
            }
        log("no audio key after $KEY_ATTEMPTS attempts: $detail")
        mutableProblems.tryEmit(SpotifyProblem.KeyFailed)
        throw KeyFailedException(detail)
    }

    private suspend fun ask(track: Bytes, file: Bytes): KeyAnswer {
        var registered: KeyRequest? = null
        try {
            val request =
                withContext(accessPointThread) {
                    val request =
                        synchronized(lock) {
                            checkLoggedIn()
                            KeyRequest(track, file, generation).also {
                                registered = it
                                keys += it
                                mutableKeysWaiting.value = keys.size
                            }
                        }
                    val current = link?.takeIf { it.generation == request.generation }
                    if (current != null && current.accessPoint.loggedIn) current.send(request)
                    request
                }
            withTimeoutOrNull(timing.loginWaitMs) { request.sent.await() }
                ?: throw SpotifyNetworkException(
                    "an audio key",
                    SocketTimeoutException("no access point login in ${timing.loginWaitMs} ms"),
                )
            return withTimeoutOrNull(timing.keyTimeoutMs) { request.answer.await() } ?: KeyAnswer.Silent
        } finally {
            registered?.let { request ->
                synchronized(lock) {
                    keys.remove(request)
                    mutableKeysWaiting.value = keys.size
                }
            }
        }
    }

    private fun checkLoggedIn() {
        if (credentials != null) return
        val refused = refusal
        if (refused == null || refused == LoginError.BAD_CREDENTIALS) throw NotLoggedInException()
        throw LoginRefusedException(refused.code, ACCESS_POINT)
    }

    private fun takeKeys(): List<KeyRequest> {
        val taken = keys.toList()
        keys.clear()
        mutableKeysWaiting.value = 0
        return taken
    }

    private fun fail(requests: List<KeyRequest>, failure: SpotifyException) {
        for (request in requests) {
            request.sent.completeExceptionally(failure)
            request.answer.completeExceptionally(failure)
        }
    }

    private suspend fun keepConnected(current: Int) {
        var attempt = 0
        var addresses = emptyList<Address>()
        if (!network.first()) {
            publish(current, SpotifyState.Offline)
            network.first { it }
        }
        while (true) {
            publish(current, SpotifyState.Connecting)
            if (addresses.isEmpty()) addresses = resolve()
            var ended: Ending = Ending.TryNext
            for (address in addresses) {
                ended = connect(address, current)
                if (ended != Ending.TryNext) break
            }
            when (ended) {
                Ending.Refused -> return
                Ending.TryNext -> addresses = emptyList()
                Ending.Dropped -> attempt = 0
            }
            publish(current, SpotifyState.Offline)
            attempt = if (awaitReconnect(attempt)) 0 else attempt + 1
        }
    }

    private suspend fun awaitReconnect(attempt: Int): Boolean {
        var networkReturned = false
        if (wanted.first()) networkReturned = pauseUnlessNetworkLost(timing.reconnectAfterMs(attempt))
        wanted.first { it }
        if (!network.first()) {
            networkReturned = true
            network.first { it }
        }
        return networkReturned
    }

    private suspend fun pauseUnlessNetworkLost(ms: Long): Boolean = coroutineScope {
        val slept = async { pause(ms) }
        val lost = async { network.first { !it } }
        select {
            slept.onAwait { false }
            lost.onAwait { true }
        }.also { coroutineContext.cancelChildren() }
    }

    private suspend fun resolve(): List<Address> {
        val url =
            endpoints.apresolve.toHttpUrl().newBuilder()
                .addQueryParameter("type", ACCESS_POINT_TYPE)
                .addQueryParameter("type", SPCLIENT_TYPE)
                .build()
        val request = Request.Builder().url(url).build()
        val body =
            try {
                val reply = resolveClient.fetch(request, MAX_RESOLVE_BYTES)
                if (!reply.ok) log("${labelOf(request)}: HTTP ${reply.status}")
                reply.body
            } catch (failed: SpotifyException) {
                log(failed.message.orEmpty())
                ByteArray(0)
            }
        val resolved = resolvedOf(body, spclients)
        spclients = resolved.spclients
        return resolved.accessPoints.mapNotNull(Address::parse)
    }

    private suspend fun connect(address: Address, current: Int): Ending {
        val socket = Socket()
        return closingOnCancel(socket) {
            val opened =
                try {
                    withContext(Dispatchers.IO) {
                        socket.connect(InetSocketAddress(address.host, address.port), timing.connectTimeoutMs)
                        socket.tcpNoDelay = true
                    }
                    true
                } catch (failed: IOException) {
                    log("access point $address: ${failed.javaClass.simpleName}")
                    false
                }
            if (opened) {
                val start = credentials ?: return@closingOnCancel Ending.TryNext
                val accessPoint = AccessPoint(identity.accessPoint, start, random::nextBytes, serverKey)
                serve(Link(socket, socket.getOutputStream(), accessPoint, current), address)
            } else {
                Ending.TryNext
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun CoroutineScope.serve(link: Link, address: Address): Ending {
        val incoming = Channel<ByteArray>(READ_QUEUE)
        val reader = launch(Dispatchers.IO) { read(link, incoming) }
        val watcher =
            launch {
                network.first { !it }
                link.close(SocketException("the network is gone"))
            }
        this@SpotifySession.link = link
        try {
            link.accessPoint.start()
            link.flush()
            val openedAt = monotonicMs()
            while (true) {
                val due =
                    link.accessPoint.deadline()
                        ?: (openedAt + timing.handshakeTimeoutMs).takeIf { !link.welcomed }
                val received: ChannelResult<ByteArray>? =
                    select {
                        incoming.onReceiveCatching { it }
                        if (due != null) onTimeout(maxOf(0, due - monotonicMs())) { null }
                    }
                if (received == null) {
                    if (!link.accessPoint.loggedIn) {
                        log("access point $address: no login in ${timing.handshakeTimeoutMs} ms")
                        return Ending.TryNext
                    }
                    link.accessPoint.advance(monotonicMs())
                } else {
                    val bytes = received.getOrNull()
                    if (bytes == null) {
                        val why = link.cause().javaClass.simpleName
                        log("access point $address: the connection closed ($why)")
                        return if (link.welcomed) Ending.Dropped else Ending.TryNext
                    }
                    link.accessPoint.receive(bytes, monotonicMs())
                }
                link.flush()
                handleEvents(link, address)?.let { return it }
            }
        } finally {
            this@SpotifySession.link = null
            reader.cancel()
            watcher.cancel()
            link.close(EOFException("the access point connection ended"))
            dropInFlight(link)
        }
    }

    private suspend fun read(link: Link, incoming: SendChannel<ByteArray>) {
        val buffer = ByteArray(READ_CHUNK)
        try {
            val input = link.socket.getInputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                incoming.send(buffer.copyOf(count))
            }
        } catch (failed: IOException) {
            link.lost = link.lost ?: failed
        } finally {
            incoming.close()
        }
    }

    private fun handleEvents(link: Link, address: Address): Ending? {
        for (event in link.accessPoint.takeEvents()) {
            when (event) {
                is ApEvent.LoggedIn -> loggedIn(link, event.reusable)

                is ApEvent.LoginFailed -> {
                    if (event.error == LoginError.TRY_ANOTHER_AP) {
                        log("access point $address: try another access point")
                        return Ending.TryNext
                    }
                    refused(link.generation, event.error)
                    return Ending.Refused
                }

                is ApEvent.Country -> synchronized(lock) {
                    if (generation == link.generation) {
                        country = event.code
                        changeOnline { it.copy(country = event.code) }
                    }
                }

                is ApEvent.Product -> synchronized(lock) {
                    if (generation == link.generation) changeOnline { it.copy(product = event.type) }
                }

                is ApEvent.AudioKey -> answer(link, event.sequence, KeyAnswer.Key(event.key))

                is ApEvent.AudioKeyRefused -> answer(link, event.sequence, KeyAnswer.Refused(event.code))

                is ApEvent.Failure -> {
                    log("access point $address: ${event.reason}")
                    mutableProblems.tryEmit(SpotifyProblem.AccessPoint)
                    return if (link.welcomed) Ending.Dropped else Ending.TryNext
                }
            }
        }
        return null
    }

    private fun loggedIn(link: Link, reusable: Credentials) {
        synchronized(lock) {
            if (generation != link.generation) return
            credentials = reusable
            listener?.loggedIn(reusable)
            mutableState.value = SpotifyState.Online(reusable.username, country)
        }
        link.welcomed = true
        log("logged in at the access point")
        val waiting =
            synchronized(lock) { keys.filter { it.link == null && it.generation == link.generation } }
        waiting.forEach(link::send)
    }

    private fun refused(current: Int, error: LoginError) {
        val dropped =
            synchronized(lock) {
                if (generation != current) return
                credentials = null
                refusal = error
                listener?.refused(error)
                mutableState.value =
                    when (error) {
                        LoginError.BAD_CREDENTIALS -> SpotifyState.Expired
                        LoginError.PREMIUM_ACCOUNT_REQUIRED -> SpotifyState.PremiumRequired
                        else -> SpotifyState.Refused(error.code)
                    }
                takeKeys()
            }
        val failure =
            if (error == LoginError.BAD_CREDENTIALS) {
                NotLoggedInException()
            } else {
                LoginRefusedException(error.code, ACCESS_POINT)
            }
        fail(dropped, failure)
        log("the access point refused the login: code ${error.code}")
        mutableProblems.tryEmit(SpotifyProblem.LoginRefused(error.code))
    }

    private fun publish(current: Int, value: SpotifyState) {
        synchronized(lock) {
            if (generation == current) mutableState.value = value
        }
    }

    private fun changeOnline(change: (SpotifyState.Online) -> SpotifyState.Online) {
        (mutableState.value as? SpotifyState.Online)?.let { mutableState.value = change(it) }
    }

    private fun answer(link: Link, sequence: Int, answer: KeyAnswer) {
        val request = synchronized(lock) { keys.firstOrNull { it.link === link && it.sequence == sequence } }
        request?.answer?.complete(answer)
    }

    private fun dropInFlight(link: Link) {
        val inFlight = synchronized(lock) { keys.filter { it.link === link } }
        val failure = SpotifyNetworkException("an audio key", link.cause())
        for (request in inFlight) request.answer.completeExceptionally(failure)
    }

    private fun monotonicMs(): Long = System.nanoTime() / NANOS_PER_MS

    private inner class Link(
        val socket: Socket,
        private val output: OutputStream,
        val accessPoint: AccessPoint,
        val generation: Int,
    ) {
        var welcomed = false

        @Volatile
        var lost: IOException? = null

        fun cause(): IOException = lost ?: EOFException("the access point closed the connection")

        fun flush() {
            val bytes = accessPoint.takeOutgoing()
            if (bytes.isEmpty()) return
            try {
                output.write(bytes)
            } catch (failed: IOException) {
                close(failed)
            }
        }

        fun send(request: KeyRequest) {
            val sequence = accessPoint.requestAudioKey(request.track, request.file) ?: return
            synchronized(lock) {
                if (request !in keys) return
                request.link = this
                request.sequence = sequence
            }
            flush()
            request.sent.complete(Unit)
        }

        fun close(reason: IOException) {
            lost = lost ?: reason
            closeQuietly(socket)
        }
    }

    private class KeyRequest(val track: Bytes, val file: Bytes, val generation: Int) {
        val sent = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<KeyAnswer>()
        var link: Link? = null
        var sequence = -1
    }

    private sealed interface KeyAnswer {
        data class Key(val key: Bytes) : KeyAnswer

        data class Refused(val code: Int) : KeyAnswer

        data object Silent : KeyAnswer
    }

    private sealed interface Ending {
        data object TryNext : Ending

        data object Refused : Ending

        data object Dropped : Ending
    }

    private data class Address(val host: String, val port: Int) {
        override fun toString(): String = "$host:$port"

        companion object {
            fun parse(text: String): Address? {
                val colon = text.lastIndexOf(':')
                val host = if (colon < 0) text else text.substring(0, colon)
                val port = if (colon < 0) DEFAULT_PORT else text.substring(colon + 1).toIntOrNull()
                if (host.isEmpty() || port == null || port !in 1..MAX_PORT) return null
                return Address(host, port)
            }
        }
    }

    internal data class Resolved(val accessPoints: List<String>, val spclients: List<String>)

    companion object {
        internal fun resolvedOf(body: ByteArray, knownSpclients: List<String>): Resolved {
            val json = jsonObjectOf(body)
            val accessPoints = json.strings(ACCESS_POINT_TYPE).mapNotNull { Address.parse(it)?.toString() }
            val hosts = json.strings(SPCLIENT_TYPE)
            return Resolved(
                accessPoints = accessPoints.ifEmpty { listOf(Endpoints.FALLBACK_ACCESS_POINT) },
                spclients = hosts.ifEmpty { knownSpclients.ifEmpty { listOf(Endpoints.FALLBACK_SPCLIENT) } },
            )
        }

        const val KEY_ATTEMPTS = 2
        const val KEY_REFUSED_FOR_ACCOUNT = 1
        const val ACCESS_POINT = "the access point"
        const val RESOLVE_TIMEOUT_MS = 10_000L
        const val MAX_RESOLVE_BYTES = 64L * 1024
        private const val ACCESS_POINT_TYPE = "accesspoint"
        private const val SPCLIENT_TYPE = "spclient"
        private const val DEFAULT_PORT = 443
        private const val MAX_PORT = 65_535
        private const val READ_CHUNK = 16 * 1024
        private const val READ_QUEUE = 16
        private const val PROBLEM_BUFFER = 16
        private const val NANOS_PER_MS = 1_000_000L
    }
}

private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
    .mapNotNull { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content }
    .filter(String::isNotEmpty)
