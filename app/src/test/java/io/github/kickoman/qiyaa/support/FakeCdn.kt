package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.spotify.SpotifyFile
import io.github.kickoman.qiyaa.spotify.protocol.AudioDecryptor
import io.github.kickoman.qiyaa.spotify.protocol.AudioFile
import io.github.kickoman.qiyaa.spotify.protocol.Bytes
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

class FakeCdn(oggBytes: Int = OGG_BYTES) : Closeable {
    sealed interface Answer {
        data object Partial : Answer

        data object Whole : Answer

        data object WithoutTotal : Answer

        data object Disconnect : Answer

        data class Short(val bytes: Int) : Answer

        data class Stall(val afterBytes: Long) : Answer

        data class Status(val code: Int) : Answer
    }

    data class Asked(val link: String, val range: String?, val userAgent: String?)

    val server = MockWebServer()
    val key: Bytes = Bytes.of(ByteArray(16) { (it * 7 + 3).toByte() })
    val plain = ByteArray(AudioFile.HEADER_SIZE + oggBytes) { (it * 31 + it / 251).toByte() }
    val ogg: ByteArray get() = plain.copyOfRange(AudioFile.HEADER_SIZE, plain.size)
    val asked = CopyOnWriteArrayList<Asked>()

    private val encrypted = ByteArray(plain.size)
    private val answers = ConcurrentHashMap<String, List<Answer>>()
    private val reserved = CopyOnWriteArrayList<Socket>()

    init {
        "OggS".toByteArray().copyInto(plain, AudioFile.HEADER_SIZE)
        plain.copyInto(encrypted)
        AudioDecryptor(key).apply(encrypted, 0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val link = request.requestUrl!!.pathSegments.first()
                    val range = request.getHeader("Range")
                    asked += Asked(link, range, request.getHeader("User-Agent"))
                    val turn = asked.count { it.link == link } - 1
                    val planned = answers[link] ?: listOf(Answer.Status(404))
                    return respond(planned[minOf(turn, planned.size - 1)], range)
                }
            }
        server.start()
    }

    fun link(name: String, vararg answers: Answer): String {
        this.answers[name] = answers.toList().ifEmpty { listOf(Answer.Partial) }
        return server.url("/$name/audio/$FILE_HEX?$TOKEN_QUERY").toString()
    }

    fun answer(name: String, answer: Answer) {
        answers[name] = List(asked.count { it.link == name } + 1) { answer }
    }

    fun refusedLink(): String {
        val socket = Socket().apply { bind(InetSocketAddress(InetAddress.getByAddress(LOOPBACK), 0)) }
        reserved += socket
        return "http://127.0.0.1:${socket.localPort}/refused/audio/$FILE_HEX?$TOKEN_QUERY"
    }

    fun file(vararg links: String): SpotifyFile =
        SpotifyFile(TRACK_ID, Bytes.ofHex(FILE_HEX)!!, KBPS, key, links.toList())

    fun links(): List<String> = asked.map { it.link }

    fun ranges(link: String): List<String?> = asked.filter { it.link == link }.map { it.range }

    override fun close() {
        try {
            server.shutdown()
        } finally {
            reserved.forEach(Socket::close)
        }
    }

    private fun respond(answer: Answer, range: String?): MockResponse {
        val from = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
        val last = range?.substringAfter('-', "")?.toIntOrNull() ?: (encrypted.size - 1)
        val to = minOf(last, encrypted.size - 1)
        return when (answer) {
            Answer.Partial -> partial(from, to)

            Answer.Whole -> MockResponse().setBody(Buffer().write(encrypted))

            Answer.WithoutTotal -> partial(from, to, total = "*")

            Answer.Disconnect ->
                partial(from, to).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

            is Answer.Short -> partial(from, minOf(to, from + answer.bytes - 1))

            is Answer.Stall -> partial(from, to).throttleBody(answer.afterBytes, 1, TimeUnit.HOURS)

            is Answer.Status -> MockResponse().setResponseCode(answer.code).setBody("refused")
        }
    }

    private fun partial(from: Int, to: Int, total: String = "${encrypted.size}"): MockResponse =
        MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $from-$to/$total")
            .setBody(Buffer().write(encrypted.copyOfRange(from, to + 1)))

    companion object {
        const val TRACK_ID = "6PCoZdjSPbRMehHviTrpB3"
        const val FILE_HEX = "0303030303030303030303030303030303030303"
        const val TOKEN_QUERY = "1729003600_secret-cdn-token"
        const val KBPS = 320
        const val OGG_BYTES = 64 * 1024
        private val LOOPBACK = byteArrayOf(127, 0, 0, 1)
    }
}
