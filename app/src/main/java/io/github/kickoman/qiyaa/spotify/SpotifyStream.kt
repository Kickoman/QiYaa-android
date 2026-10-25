package io.github.kickoman.qiyaa.spotify

import io.github.kickoman.qiyaa.spotify.protocol.AudioDecryptor
import io.github.kickoman.qiyaa.spotify.protocol.AudioFile
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

interface ReadGuard {
    fun <T> during(call: Call?, block: () -> T): T

    companion object {
        val NONE: ReadGuard =
            object : ReadGuard {
                override fun <T> during(call: Call?, block: () -> T): T = block()
            }
    }
}

class SpotifyStream(
    private val calls: Call.Factory,
    private val file: SpotifyFile,
    private val userAgent: String,
    private val relink: () -> List<String>,
    private val guard: ReadGuard = ReadGuard.NONE,
    private val report: (SpotifyProblem) -> Unit = {},
    private val log: (String) -> Unit = {},
) : Closeable {
    private sealed interface LinkFailure {
        class Network(val cause: IOException) : LinkFailure

        class Status(val code: Int) : LinkFailure
    }

    private class Reply(val call: Call, val response: Response, var skip: Long) {
        private val body = response.body.source()
        var delivered = 0L

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (skip > 0) {
                body.skip(skip)
                skip = 0
            }
            return body.read(buffer, offset, length)
        }
    }

    private val decryptor = AudioDecryptor(file.key)
    private var links: List<HttpUrl> = file.urls.map { it.toHttpUrl() }
    private var linkIndex = 0
    private var reply: Reply? = null
    private var position = 0L
    private var rangeEnd = UNKNOWN
    private var fileLength = UNKNOWN
    private var failures = 0
    private var allExpired = true
    private var relinked = false
    private var networkFailure: Pair<String, IOException>? = null

    val oggLength: Long get() = if (fileLength == UNKNOWN) UNKNOWN else fileLength - AudioFile.HEADER_SIZE

    init {
        require(links.isNotEmpty()) { "no CDN link for track ${file.trackId}" }
    }

    fun open(oggPosition: Long, length: Long = UNKNOWN): Long {
        require(oggPosition >= 0) { "an Ogg position of $oggPosition" }
        require(length >= 0 || length == UNKNOWN) { "a length of $length" }
        require(fileLength == UNKNOWN || oggPosition <= oggLength) {
            "Ogg position $oggPosition is past the end of $oggLength bytes"
        }
        close()
        position = oggPosition + AudioFile.HEADER_SIZE
        rangeEnd = if (length == UNKNOWN) UNKNOWN else position + length
        if (rangeEnd != UNKNOWN && fileLength != UNKNOWN) rangeEnd = minOf(rangeEnd, fileLength)
        newStreak()
        if (length == 0L || position == fileLength) {
            rangeEnd = position
            return if (length == UNKNOWN) 0 else length
        }
        reply = connect()
        return when {
            length != UNKNOWN -> length
            fileLength != UNKNOWN -> fileLength - position
            else -> UNKNOWN
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val limit = limit()
            if (limit != UNKNOWN && position >= limit) return END
            val current = reply ?: connect().also { reply = it }
            val wanted = if (limit == UNKNOWN) length else minOf(length.toLong(), limit - position).toInt()
            val count =
                try {
                    guard.during(current.call) { current.read(buffer, offset, wanted) }
                } catch (failed: IOException) {
                    if (canceled(current.call)) throw failed
                    if (current.delivered > 0) brokeOff(failed) else linkFailed(LinkFailure.Network(failed))
                    continue
                }
            if (count == END) {
                if (limit == UNKNOWN) return END
                dropReply()
                if (current.delivered == 0L) {
                    linkFailed(
                        LinkFailure.Network(EOFException("the reply ended at byte $position of $limit")),
                    )
                }
                continue
            }
            decryptor.apply(buffer, position, offset, count)
            position += count
            current.delivered += count
            newStreak()
            return count
        }
    }

    override fun close() = dropReply()

    private fun connect(): Reply {
        while (true) {
            val request =
                Request.Builder()
                    .url(links[linkIndex])
                    .header(RANGE, rangeHeader())
                    .header(USER_AGENT, userAgent)
                    .build()
            var call: Call? = null
            val response =
                try {
                    guard.during(null) { calls.newCall(request).also { call = it }.execute() }
                } catch (failed: IOException) {
                    if (canceled(call)) throw failed
                    linkFailed(LinkFailure.Network(failed))
                    continue
                }
            val status = response.code
            if (status != OK && status != PARTIAL) {
                response.close()
                linkFailed(LinkFailure.Status(status))
                continue
            }
            val length = if (status == OK) response.body.contentLength() else totalOf(response)
            if (length >= 0) fileLength = length
            return Reply(checkNotNull(call), response, skip = if (status == OK) position else 0)
        }
    }

    private fun linkFailed(failure: LinkFailure) {
        dropReply()
        val label = labelOf(links[linkIndex])
        log("GET $label: ${describe(failure)}")
        failures++
        if (failure is LinkFailure.Network) networkFailure = label to failure.cause
        if (failure !is LinkFailure.Status || failure.code !in EXPIRED) allExpired = false
        linkIndex = (linkIndex + 1) % links.size
        if (failures < links.size) return
        if (allExpired && !relinked) {
            log("every CDN link of track ${file.trackId} answered 403 or 410: storage-resolve once more")
            val fresh = relink().map { it.toHttpUrl() }
            relinked = true
            if (fresh.isNotEmpty()) {
                links = fresh
                linkIndex = 0
                failures = 0
                return
            }
        }
        throw when (failure) {
            is LinkFailure.Network -> SpotifyNetworkException("GET $label", failure.cause)

            is LinkFailure.Status ->
                networkFailure?.let { (where, cause) -> SpotifyNetworkException("GET $where", cause) }
                    ?: statusFailure(failure.code, label)
        }
    }

    private fun statusFailure(status: Int, label: String): CdnStatusException {
        report(SpotifyProblem.Http(status))
        return CdnStatusException(status, label)
    }

    private fun brokeOff(failed: IOException) {
        dropReply()
        val label = labelOf(links[linkIndex])
        log("GET $label: ${failed.javaClass.simpleName} after some bytes, on from byte $position")
        linkIndex = (linkIndex + 1) % links.size
    }

    private fun newStreak() {
        failures = 0
        allExpired = true
        relinked = false
        networkFailure = null
    }

    private fun dropReply() {
        reply?.response?.close()
        reply = null
    }

    private fun limit(): Long = when {
        rangeEnd == UNKNOWN -> fileLength
        fileLength == UNKNOWN -> rangeEnd
        else -> minOf(rangeEnd, fileLength)
    }

    private fun rangeHeader(): String =
        "bytes=$position-" + if (rangeEnd == UNKNOWN) "" else "${rangeEnd - 1}"

    private fun canceled(call: Call?): Boolean =
        Thread.currentThread().isInterrupted || call?.isCanceled() == true

    private fun totalOf(response: Response): Long {
        val total = response.header(CONTENT_RANGE)?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
        if (total != null) return total
        val replyLength = response.body.contentLength()
        return if (rangeEnd == UNKNOWN && replyLength >= 0) position + replyLength else UNKNOWN
    }

    private fun describe(failure: LinkFailure): String = when (failure) {
        is LinkFailure.Network -> failure.cause.javaClass.simpleName
        is LinkFailure.Status -> "HTTP ${failure.code}"
    }

    private fun labelOf(url: HttpUrl): String = "${url.host}${url.encodedPath}"

    companion object {
        const val UNKNOWN = -1L
        const val END = -1
        const val RANGE = "Range"
        const val CONTENT_RANGE = "Content-Range"
        const val USER_AGENT = "User-Agent"
        val EXPIRED = setOf(403, 410)
        private const val OK = 200
        private const val PARTIAL = 206
    }
}
