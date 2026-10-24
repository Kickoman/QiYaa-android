package io.github.kickoman.qiyaa.lyrics

import io.github.kickoman.qiyaa.track.Track
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class Lrclib(
    client: OkHttpClient,
    appVersion: String,
    baseUrl: String = BASE_URL,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) : LyricsSource {
    private val client =
        client.newBuilder()
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followSslRedirects(false)
            .build()
    private val searchUrl: HttpUrl = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/search").build()
    private val userAgent = "QiYaa/$appVersion ($REPOSITORY_URL)"

    private sealed interface Reply {
        data class Records(val records: List<LrclibRecord>) : Reply

        data object Nothing : Reply

        data object Overloaded : Reply

        data object Failed : Reply
    }

    private sealed interface Outcome {
        data class Status(val code: Int, val body: String) : Outcome

        data object Network : Outcome

        data object TooLarge : Outcome
    }

    override suspend fun lyrics(track: Track): LyricsAnswer {
        val artist = track.artists.firstOrNull()?.trim().orEmpty()
        val title = track.title.trim()
        if (artist.isEmpty() || title.isEmpty()) return LyricsAnswer.Missing()
        var serverError = false
        for (name in listOfNotNull(title, LrclibSearch.titleWithoutVersion(title))) {
            when (val reply = searchTwice(name, artist)) {
                is Reply.Records -> {
                    val choice = LrclibSearch.pick(reply.records, track.durationMs)
                    if (choice.answer !is LyricsAnswer.Missing) return choice.answer
                }

                Reply.Nothing -> Unit

                Reply.Overloaded -> serverError = true

                Reply.Failed -> return LyricsAnswer.Failed
            }
        }
        return LyricsAnswer.Missing(serverError)
    }

    private suspend fun searchTwice(title: String, artist: String): Reply {
        val first = search(title, artist)
        if (!first.retried()) return first.reply()
        delay(retryDelayMs)
        val second = search(title, artist)
        return when {
            second == Outcome.TooLarge -> Reply.Failed
            second is Outcome.Status && second.code == OVERLOADED -> Reply.Overloaded
            second.retried() -> Reply.Failed
            else -> second.reply()
        }
    }

    private fun Outcome.retried(): Boolean =
        this == Outcome.Network || (this is Outcome.Status && code >= 500)

    private fun Outcome.reply(): Reply = when (this) {
        Outcome.TooLarge, Outcome.Network -> Reply.Failed
        is Outcome.Status -> if (code == OK) Reply.Records(LrclibSearch.parse(body)) else Reply.Nothing
    }

    private suspend fun search(title: String, artist: String): Outcome =
        suspendCancellableCoroutine { continuation ->
            val url =
                searchUrl.newBuilder()
                    .addQueryParameter("track_name", title)
                    .addQueryParameter("artist_name", artist)
                    .build()
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(Outcome.Network)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome =
                            try {
                                response.use(::read)
                            } catch (failed: IOException) {
                                Outcome.Network
                            }
                        continuation.resume(outcome)
                    }
                },
            )
        }

    private fun read(response: Response): Outcome {
        if (response.code != OK) return Outcome.Status(response.code, "")
        if (response.body.contentLength() > MAX_REPLY_BYTES) return Outcome.TooLarge
        val source = response.body.source()
        if (source.request(MAX_REPLY_BYTES + 1)) return Outcome.TooLarge
        return Outcome.Status(OK, source.buffer.readUtf8())
    }

    companion object {
        const val BASE_URL = "https://lrclib.net"
        const val REPOSITORY_URL = "https://github.com/Kickoman/QiYaa-android"
        const val TIMEOUT_MS = 8_000L
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_REPLY_BYTES = 2L * 1024 * 1024
        private const val OK = 200
        private const val OVERLOADED = 503
    }
}
