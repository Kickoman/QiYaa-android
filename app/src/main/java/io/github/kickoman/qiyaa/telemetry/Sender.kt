package io.github.kickoman.qiyaa.telemetry

import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed interface Answer {
    data class Accepted(val status: Int) : Answer

    data class Dropped(val status: Int) : Answer

    data class RetryAfter(val seconds: Long) : Answer

    data class Failed(val status: Int?) : Answer

    companion object {
        const val DEFAULT_RETRY_SECONDS = 60L
        const val MAX_RETRY_SECONDS = 3_600L

        fun of(status: Int?, retryAfter: String?): Answer = when {
            status == null -> Failed(null)
            status in 200..299 -> Accepted(status)
            status == 400 || status == 413 -> Dropped(status)
            status == 429 ->
                RetryAfter(
                    retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_RETRY_SECONDS)
                        ?: DEFAULT_RETRY_SECONDS,
                )
            else -> Failed(status)
        }
    }
}

object Backoff {
    private const val MAX_DOUBLINGS = 6

    fun seconds(failures: Int): Long = minOf(
        Answer.MAX_RETRY_SECONDS,
        Answer.DEFAULT_RETRY_SECONDS shl (failures - 1).coerceIn(0, MAX_DOUBLINGS),
    )
}

class Sender(private val client: OkHttpClient, endpoint: String) {
    private val url: HttpUrl = endpoint.toHttpUrl()

    suspend fun post(body: String): Answer = suspendCancellableCoroutine { continuation ->
        val content = body.toByteArray(Charsets.UTF_8).toRequestBody(JSON)
        val call = client.newCall(Request.Builder().url(url).post(content).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(Answer.Failed(null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val answer = response.use { Answer.of(it.code, it.header(RETRY_AFTER)) }
                    continuation.resume(answer)
                }
            },
        )
    }

    companion object {
        const val RETRY_AFTER = "Retry-After"
        private val JSON = "application/json".toMediaType()
    }
}
