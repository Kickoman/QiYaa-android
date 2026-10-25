package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.spotify.Endpoints
import io.github.kickoman.qiyaa.spotify.SessionTiming
import io.github.kickoman.qiyaa.spotify.SpotifyIdentity
import io.github.kickoman.qiyaa.spotify.SpotifyProblem
import io.github.kickoman.qiyaa.spotify.SpotifySession
import io.github.kickoman.qiyaa.spotify.SpotifyState
import io.github.kickoman.qiyaa.spotify.SpotifyWeb
import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.ProtoWriter
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

class SpotifyStack(
    timing: SessionTiming = SessionTiming(keyRetryMs = 10),
    inUse: Boolean = true,
    maxBodyBytes: Long = SpotifyWeb.MAX_BODY_BYTES,
    elapsedNanos: () -> Long = System::nanoTime,
    deviceId: String = DEVICE_ID,
) : Closeable {
    val http = MockWebServer()
    val accessPoint = FakeAccessPointServer()
    val network = MutableStateFlow(true)
    val inUse = MutableStateFlow(inUse)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pauses = MutableStateFlow<List<Long>>(emptyList())
    val webPauses = CopyOnWriteArrayList<Long>()
    val logged = CopyOnWriteArrayList<String>()
    val problems = MutableStateFlow<List<SpotifyProblem>>(emptyList())
    val clock = AtomicLong(1_000_000_000L)

    @Volatile
    var maxPauses = Int.MAX_VALUE

    @Volatile
    var onLog: (String) -> Unit = {}

    @Volatile
    var accessPoints: List<String> = listOf(accessPoint.address)

    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private val reserved = CopyOnWriteArrayList<Socket>()

    val identity = SpotifyIdentity(APP_VERSION, deviceId, "6.1.25-android16", "16", "arm64-v8a")
    val session: SpotifySession
    val web: SpotifyWeb

    init {
        http.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recorded += request
                    val route = routes["${request.method} ${request.requestUrl!!.encodedPath}"]
                    return route?.invoke(request) ?: MockResponse().setResponseCode(404)
                }
            }
        http.start()
        route("GET", "/") { json(apresolve()) }
        route("POST", "/v1/clienttoken") { protobuf(clientToken(CLIENT_TOKEN)) }
        route("POST", "/v3/login") { protobuf(login5(BEARER)) }
        session =
            SpotifySession(
                client = OkHttpClient(),
                identity = identity,
                network = network,
                inUse = this.inUse,
                scope = scope,
                endpoints = endpoints(),
                timing = timing,
                serverKey = FakeAccessPointKeys.serverKey,
                pause = { ms ->
                    pauses.update { it + ms }
                    if (pauses.value.size >= maxPauses) awaitCancellation()
                    yield()
                },
                log = {
                    logged += it
                    onLog(it)
                },
            )
        web =
            SpotifyWeb(
                client = OkHttpClient(),
                session = session,
                identity = identity,
                endpoints = endpoints(),
                clock = clock::get,
                pause = { webPauses += it },
                maxBodyBytes = maxBodyBytes,
                elapsedNanos = elapsedNanos,
                log = { logged += it },
            )
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.problems.collect {
                problems.update { list ->
                    list +
                        it
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            web.problems.collect {
                problems.update { list ->
                    list +
                        it
                }
            }
        }
    }

    fun endpoints(): Endpoints {
        val base = http.url("/").toString().removeSuffix("/")
        return Endpoints(
            apresolve = base,
            accounts = base,
            clientToken = base,
            login5 = base,
            pathfinder = base,
            spclientScheme = "http",
        )
    }

    fun route(method: String, path: String, answer: (RecordedRequest) -> MockResponse) {
        routes["$method $path"] = answer
    }

    fun requests(path: String): List<RecordedRequest> = recorded.filter {
        it.requestUrl!!.encodedPath == path
    }

    suspend fun loginStored(credentials: Credentials = STORED): SpotifyState.Online {
        session.login(credentials)
        return awaitOnline()
    }

    suspend fun awaitOnline(): SpotifyState.Online =
        withTimeout(WAIT_MS) { session.state.first { it is SpotifyState.Online && it.product.isNotEmpty() } }
            as SpotifyState.Online

    suspend fun awaitState(state: SpotifyState): SpotifyState =
        withTimeout(WAIT_MS) { session.state.first { it == state } }

    suspend fun awaitProblems(count: Int): List<SpotifyProblem> =
        withTimeout(WAIT_MS) { problems.first { it.size >= count } }

    suspend fun awaitPauses(count: Int): List<Long> = withTimeout(WAIT_MS) {
        pauses.first { it.size >= count }
    }

    fun refusingAddress(): String {
        val socket = Socket().apply { bind(InetSocketAddress(InetAddress.getByAddress(LOOPBACK), 0)) }
        reserved += socket
        return "127.0.0.1:${socket.localPort}"
    }

    override fun close() {
        session.logout()
        accessPoint.close()
        scope.cancel()
        http.shutdown()
        reserved.forEach(Socket::close)
    }

    private fun apresolve(): JsonObject = JsonObject(
        mapOf(
            "accesspoint" to JsonArray(accessPoints.map(::JsonPrimitive)),
            "spclient" to JsonArray(listOf(JsonPrimitive("127.0.0.1:${http.port}"))),
        ),
    )

    companion object {
        const val APP_VERSION = "0.8.0"
        const val CLIENT_TOKEN = "client-token-value"
        const val BEARER = "bearer-token"
        const val WAIT_MS = 10_000L
        val DEVICE_ID = "d".repeat(40)
        val STORED =
            Credentials("owner", AuthenticationType.STORED_CREDENTIALS, Bytes.of(byteArrayOf(1, 2, 3)))

        fun json(body: JsonObject): MockResponse =
            MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

        fun protobuf(body: ByteArray): MockResponse =
            MockResponse().setHeader("Content-Type", SpotifyWeb.PROTOBUF).setBody(Buffer().write(body))

        fun clientToken(token: String, refreshAfterSeconds: Long = 1_209_600): ByteArray =
            ProtoWriter().varint(
                1,
                1,
            ).message(2, ProtoWriter().string(1, token).varint(3, refreshAfterSeconds))
                .toByteArray()

        fun login5(bearer: String, expiresInSeconds: Long = 3_600, username: String = "owner"): ByteArray =
            ProtoWriter()
                .message(1, ProtoWriter().string(1, username).string(2, bearer).varint(4, expiresInSeconds))
                .toByteArray()
        private val LOOPBACK = byteArrayOf(127, 0, 0, 1)
    }
}

fun RecordedRequest.bytes(): ByteArray = body.clone().readByteArray()
