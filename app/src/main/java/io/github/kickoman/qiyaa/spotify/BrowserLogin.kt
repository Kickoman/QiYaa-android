package io.github.kickoman.qiyaa.spotify

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class BrowserLogin(
    private val client: OkHttpClient,
    private val endpoints: Endpoints = Endpoints(),
    private val random: SecureRandom = SecureRandom(),
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    data class Pages(val loggingIn: String, val refused: String)

    suspend fun login(pages: Pages, openPage: (String) -> Unit): String {
        val server = ServerSocket()
        val verifier = randomText(VERIFIER_LENGTH)
        val state = randomText(STATE_LENGTH)
        lateinit var redirectUri: String
        val code =
            closingOnCancel(server) {
                withContext(Dispatchers.IO) {
                    server.bind(InetSocketAddress(InetAddress.getByAddress(LOOPBACK), 0), 1)
                }
                redirectUri = "http://127.0.0.1:${server.localPort}$LOGIN_PATH"
                val redirect = CompletableDeferred<Redirect>()
                val acceptor = launch(Dispatchers.IO) { accept(server, state, pages, redirect) }
                openPage(authorizeUrl(redirectUri, challengeOf(verifier), state).toString())
                val answered = redirect.await()
                closeQuietly(server)
                acceptor.cancel()
                when (answered) {
                    is Redirect.Code -> answered.code
                    is Redirect.Refused -> throw AuthorizationException(answered.error, "GET $LOGIN_PATH")
                }
            }
        val reply =
            client.postForm(
                endpoints.accounts,
                PairLogin.TOKEN_PATH,
                listOf(
                    PairLogin.GRANT_TYPE to AUTHORIZATION_CODE,
                    CODE to code,
                    REDIRECT_URI to redirectUri,
                    PairLogin.CLIENT_ID to SpotifyIdentity.CLIENT_ID,
                    CODE_VERIFIER to verifier,
                ),
            )
        return reply.text(PairLogin.ACCESS_TOKEN).ifEmpty { throw reply.failure() }
    }

    private suspend fun accept(
        server: ServerSocket,
        state: String,
        pages: Pages,
        redirect: CompletableDeferred<Redirect>,
    ) {
        withContext(Dispatchers.IO) {
            try {
                while (true) {
                    val socket = server.accept()
                    val handler =
                        launch {
                            closingOnCancel(socket) {
                                answer(socket, state, pages)?.let(redirect::complete)
                            }
                        }
                    handler.invokeOnCompletion { closeQuietly(socket) }
                }
            } catch (ignored: IOException) {
                // The server socket closes when the login ends: nothing more to accept.
            }
        }
    }

    private fun answer(socket: Socket, state: String, pages: Pages): Redirect? {
        val head =
            try {
                socket.soTimeout = readTimeoutMs
                readHead(socket.getInputStream())
            } catch (ignored: IOException) {
                // A connection that sends nothing in time is not the browser's redirect.
                return null
            }
        val requestLine = head.substringBefore("\r\n").split(' ')
        val target = requestLine.getOrNull(1).orEmpty()
        val url = "http://127.0.0.1$target".toHttpUrlOrNull()
        if (requestLine.firstOrNull() != "GET" || url == null || url.encodedPath != LOGIN_PATH) {
            write(socket, NOT_FOUND)
            return null
        }
        val code = url.queryParameter(CODE).orEmpty()
        val error = url.queryParameter(ERROR).orEmpty().take(MAX_ERROR_CHARS)
        val matches = url.queryParameter(STATE) == state
        if (code.isEmpty() || !matches) {
            write(socket, page(pages.refused))
            return Redirect.Refused(error.ifEmpty { if (matches) NO_CODE else STATE_MISMATCH })
        }
        write(socket, page(pages.loggingIn))
        return Redirect.Code(code)
    }

    private fun readHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        while (head.size() < MAX_HEAD_BYTES) {
            val count = input.read(buffer)
            if (count < 0) break
            head.write(buffer, 0, count)
            if (head.toString(Charsets.ISO_8859_1.name()).contains("\r\n\r\n")) break
        }
        return head.toString(Charsets.ISO_8859_1.name())
    }

    private fun write(socket: Socket, response: String) {
        try {
            socket.getOutputStream().apply {
                write(response.toByteArray(Charsets.UTF_8))
                flush()
            }
        } catch (ignored: IOException) {
            // The browser went away; the redirect, if it came, still counts.
        }
    }

    private fun page(text: String): String {
        val body =
            "<!doctype html><meta charset=\"utf-8\"><title>QiYaa</title><p>${escape(text)}</p>"
        val size = body.toByteArray(Charsets.UTF_8).size
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n" +
            "Content-Length: $size\r\n\r\n$body"
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun authorizeUrl(redirectUri: String, challenge: String, state: String): HttpUrl =
        (endpoints.accounts + AUTHORIZE_PATH).toHttpUrl().newBuilder()
            .addQueryParameter(RESPONSE_TYPE, CODE)
            .addQueryParameter(PairLogin.CLIENT_ID, SpotifyIdentity.CLIENT_ID)
            .addQueryParameter(REDIRECT_URI, redirectUri)
            .addQueryParameter(PairLogin.SCOPE, PairLogin.STREAMING)
            .addQueryParameter(CODE_CHALLENGE_METHOD, S256)
            .addQueryParameter(CODE_CHALLENGE, challenge)
            .addQueryParameter(STATE, state)
            .build()

    private fun randomText(length: Int): String =
        String(CharArray(length) { UNRESERVED[random.nextInt(UNRESERVED.length)] })

    private sealed interface Redirect {
        data class Code(val code: String) : Redirect {
            override fun toString(): String = "Code(<${code.length} chars>)"
        }

        data class Refused(val error: String) : Redirect
    }

    companion object {
        const val AUTHORIZE_PATH = "/authorize"
        const val LOGIN_PATH = "/login"
        const val RESPONSE_TYPE = "response_type"
        const val CODE = "code"
        const val ERROR = "error"
        const val STATE = "state"
        const val REDIRECT_URI = "redirect_uri"
        const val CODE_CHALLENGE = "code_challenge"
        const val CODE_CHALLENGE_METHOD = "code_challenge_method"
        const val CODE_VERIFIER = "code_verifier"
        const val S256 = "S256"
        const val AUTHORIZATION_CODE = "authorization_code"
        const val STATE_MISMATCH = "state_mismatch"
        const val NO_CODE = "no_code"
        const val VERIFIER_LENGTH = 64
        const val STATE_LENGTH = 24
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_HEAD_BYTES = 16 * 1024
        private const val READ_CHUNK = 2 * 1024
        private const val MAX_ERROR_CHARS = 64
        private const val UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private const val NOT_FOUND =
            "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
        private val LOOPBACK = byteArrayOf(127, 0, 0, 1)

        fun challengeOf(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
