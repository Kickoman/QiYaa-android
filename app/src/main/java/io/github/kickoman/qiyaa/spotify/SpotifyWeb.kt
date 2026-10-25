package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AuthenticationType
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import io.github.kickoman.qiyaa.spotify.protocol.ClientTokenReply
import io.github.kickoman.qiyaa.spotify.protocol.Credentials
import io.github.kickoman.qiyaa.spotify.protocol.Hashcash
import io.github.kickoman.qiyaa.spotify.protocol.HashcashChallenge
import io.github.kickoman.qiyaa.spotify.protocol.HashcashSolution
import io.github.kickoman.qiyaa.spotify.protocol.Login
import io.github.kickoman.qiyaa.spotify.protocol.Login5Reply
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class WebRequest(
    val path: String,
    val method: String = "GET",
    val body: Bytes? = null,
    val contentType: String = SpotifyWeb.PROTOBUF,
    val accept: String = SpotifyWeb.PROTOBUF,
    val accountQuery: Boolean = true,
    val label: String = path.substringBefore('?'),
)

class SpotifyWeb(
    private val client: OkHttpClient,
    private val session: SpotifySession,
    private val identity: SpotifyIdentity,
    private val endpoints: Endpoints = Endpoints(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
    private val solver: CoroutineDispatcher = Dispatchers.Default,
    private val elapsedNanos: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    private val tokens = Mutex()
    private var clientToken = ""
    private var clientTokenUntilMs = 0L
    private var bearer = ""
    private var bearerUntilMs = 0L
    private var bearerUsername = ""

    private val mutableProblems = MutableSharedFlow<SpotifyProblem>(extraBufferCapacity = PROBLEM_BUFFER)
    val problems: SharedFlow<SpotifyProblem> = mutableProblems.asSharedFlow()

    suspend fun spclient(request: WebRequest): ByteArray {
        val host = session.spclients.firstOrNull() ?: Endpoints.FALLBACK_SPCLIENT
        return send(request, "${endpoints.spclientScheme}://$host")
    }

    suspend fun pathfinder(query: String): ByteArray = send(
        WebRequest(
            path = PATHFINDER_PATH,
            method = "POST",
            body = Bytes.of(query.toByteArray(Charsets.UTF_8)),
            contentType = JSON_UTF_8,
            accept = JSON,
            accountQuery = false,
        ),
        endpoints.pathfinder,
    )

    fun report(problem: SpotifyProblem) {
        mutableProblems.tryEmit(problem)
    }

    private suspend fun send(request: WebRequest, base: String): ByteArray {
        var retried = false
        while (true) {
            val credentials = session.credentials ?: throw NotLoggedInException()
            val (token, bearerToken) = tokensFor(credentials)
            val reply = fetch(build(request, base, token, bearerToken), maxBodyBytes, label(request, base))
            if (!retried && reply.status == UNAUTHORIZED) {
                forgetBearer(bearerToken)
                retried = true
                continue
            }
            val retryAfterMs = (reply.retryAfterSeconds ?: 0) * MS_PER_SECOND
            if (!retried && reply.status == TOO_MANY_REQUESTS && retryAfterMs <= LONGEST_RETRY_AFTER_MS) {
                pause(retryAfterMs)
                retried = true
                continue
            }
            if (!reply.ok) throw httpFailure(reply.status, request.method, request.label, base)
            return reply.body
        }
    }

    private fun build(request: WebRequest, base: String, token: String, bearerToken: String): Request {
        val url = (base + request.path).toHttpUrl().newBuilder()
        if (request.accountQuery) {
            url.addQueryParameter("product", "0")
                .addQueryParameter("country", session.country)
                .addQueryParameter("salt", Random.nextInt().toUInt().toString())
        }
        val body =
            if (request.method == "GET" || request.method == "HEAD") {
                null
            } else {
                (request.body?.toByteArray() ?: ByteArray(0)).toRequestBody(request.contentType.toMediaType())
            }
        return Request.Builder()
            .url(url.build())
            .header(AUTHORIZATION, "Bearer $bearerToken")
            .header(CLIENT_TOKEN, token)
            .header(USER_AGENT, identity.userAgent)
            .header(ACCEPT, request.accept)
            .method(request.method, body)
            .build()
    }

    private suspend fun tokensFor(credentials: Credentials): Pair<String, String> = tokens.withLock {
        val now = clock()
        if (clientToken.isEmpty() || now >= clientTokenUntilMs) fetchClientToken()
        if (bearer.isEmpty() || now >= bearerUntilMs || bearerUsername != credentials.username) {
            fetchBearer(credentials)
        }
        clientToken to bearer
    }

    private suspend fun forgetBearer(used: String) {
        tokens.withLock {
            if (bearer == used) bearer = ""
        }
    }

    private suspend fun fetchClientToken() {
        var body = Login.clientTokenRequest(identity.clientToken)
        for (round in 0..MAX_CHALLENGE_ROUNDS) {
            val reply = post(endpoints.clientToken, CLIENT_TOKEN_PATH, body, token = null)
            when (val parsed = Login.parseClientToken(reply)) {
                is ClientTokenReply.Granted -> {
                    clientToken = parsed.token
                    clientTokenUntilMs =
                        clock() + parsed.refreshAfterSeconds * MS_PER_SECOND - REFRESH_EARLY_MS
                    return
                }

                is ClientTokenReply.Challenge -> {
                    val hashcash = parsed.hashcash
                    if (hashcash == null || round == MAX_CHALLENGE_ROUNDS) {
                        throw contentFailure(CLIENT_TOKEN_PATH, "no client token after $round challenges")
                    }
                    val solution =
                        solve(Bytes.EMPTY, hashcash)
                            ?: throw contentFailure(CLIENT_TOKEN_PATH, "hashcash not solved in time")
                    body = Login.clientTokenAnswer(parsed.state, solution.suffix)
                }

                is ClientTokenReply.Failure -> throw contentFailure(CLIENT_TOKEN_PATH, parsed.reason)
            }
        }
    }

    private suspend fun fetchBearer(credentials: Credentials) {
        if (credentials.type != AuthenticationType.STORED_CREDENTIALS || credentials.data.isEmpty()) {
            throw NotLoggedInException()
        }
        var context = Bytes.EMPTY
        var solution: HashcashSolution? = null
        for (round in 0..MAX_CHALLENGE_ROUNDS) {
            val body = Login.login5Request(
                SpotifyIdentity.CLIENT_ID,
                identity.deviceId,
                credentials,
                context,
                solution,
            )
            val reply = post(endpoints.login5, LOGIN5_PATH, body, token = clientToken)
            when (val parsed = Login.parseLogin5(reply)) {
                is Login5Reply.Ok -> {
                    bearer = parsed.accessToken
                    bearerUntilMs = clock() + parsed.expiresInSeconds * MS_PER_SECOND - REFRESH_EARLY_MS
                    bearerUsername = credentials.username
                    return
                }

                is Login5Reply.Challenge -> {
                    if (round == MAX_CHALLENGE_ROUNDS) {
                        throw contentFailure(LOGIN5_PATH, "no access token after $round challenges")
                    }
                    context = parsed.context
                    solution =
                        solve(parsed.context, parsed.hashcash)
                            ?: throw contentFailure(LOGIN5_PATH, "hashcash not solved in time")
                }

                is Login5Reply.Error -> {
                    log("POST ${hostOf(endpoints.login5)}$LOGIN5_PATH: login5 error ${parsed.code}")
                    mutableProblems.tryEmit(SpotifyProblem.Http(UNAUTHORIZED))
                    throw LoginRefusedException(parsed.code, LOGIN5_PATH)
                }

                is Login5Reply.Failure -> throw contentFailure(LOGIN5_PATH, parsed.reason)
            }
        }
    }

    private suspend fun post(base: String, path: String, body: ByteArray, token: String?): ByteArray {
        val request = Request.Builder()
            .url((base + path).toHttpUrl())
            .header(ACCEPT, PROTOBUF)
            .header(USER_AGENT, identity.userAgent)
            .apply { if (token != null) header(CLIENT_TOKEN, token) }
            .post(body.toRequestBody(PROTOBUF.toMediaType()))
            .build()
        val reply = fetch(request, MAX_TOKEN_REPLY_BYTES, labelOf(request))
        if (!reply.ok) throw httpFailure(reply.status, "POST", path, base)
        return reply.body
    }

    private suspend fun fetch(request: Request, maxBytes: Long, label: String): HttpReply = try {
        client.fetch(request, maxBytes, label)
    } catch (failed: SpotifyContentException) {
        log(failed.message.orEmpty())
        mutableProblems.tryEmit(SpotifyProblem.Content)
        throw failed
    }

    private suspend fun solve(context: Bytes, challenge: HashcashChallenge): HashcashSolution? =
        withContext(solver) {
            val started = elapsedNanos()
            val suffix =
                Hashcash.solve(context, challenge) {
                    !isActive || elapsedNanos() - started >= Hashcash.GIVE_UP_AFTER_MS * NANOS_PER_MS
                }
            suffix?.let { HashcashSolution(it, elapsedNanos() - started) }
        }

    private fun httpFailure(status: Int, method: String, path: String, base: String): SpotifyHttpException {
        log("$method ${hostOf(base)}$path: HTTP $status")
        mutableProblems.tryEmit(SpotifyProblem.Http(status))
        return SpotifyHttpException(status, method, path)
    }

    private fun contentFailure(path: String, detail: String): SpotifyContentException {
        log("POST $path: $detail")
        mutableProblems.tryEmit(SpotifyProblem.Content)
        return SpotifyContentException("POST $path: $detail")
    }

    private fun label(request: WebRequest, base: String): String =
        "${request.method} ${hostOf(base)}${request.label}"

    private fun hostOf(base: String): String = base.toHttpUrl().host

    companion object {
        const val PROTOBUF = "application/x-protobuf"
        const val JSON = "application/json"
        const val JSON_UTF_8 = "application/json;charset=UTF-8"
        const val CLIENT_TOKEN_PATH = "/v1/clienttoken"
        const val LOGIN5_PATH = "/v3/login"
        const val PATHFINDER_PATH = "/pathfinder/v2/query"
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val MAX_TOKEN_REPLY_BYTES = 64L * 1024
        const val LONGEST_RETRY_AFTER_MS = 5_000L
        const val REFRESH_EARLY_MS = 60_000L
        const val MAX_CHALLENGE_ROUNDS = 3
        const val AUTHORIZATION = "Authorization"
        const val CLIENT_TOKEN = "client-token"
        const val USER_AGENT = "User-Agent"
        const val ACCEPT = "Accept"
        private const val UNAUTHORIZED = 401
        private const val TOO_MANY_REQUESTS = 429
        private const val MS_PER_SECOND = 1_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val PROBLEM_BUFFER = 16
    }
}
