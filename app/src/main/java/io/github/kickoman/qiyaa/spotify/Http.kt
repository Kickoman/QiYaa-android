package io.github.kickoman.qiyaa.spotify

import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class HttpReply(val status: Int, val body: ByteArray, val retryAfterSeconds: Long?) {
    val ok: Boolean get() = status in 200..299
}

internal fun labelOf(request: Request): String =
    "${request.method} ${request.url.host}${request.url.encodedPath}"

internal suspend fun OkHttpClient.fetch(
    request: Request,
    maxBytes: Long,
    label: String = labelOf(request),
): HttpReply = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(SpotifyNetworkException(label, e))
            }

            override fun onResponse(call: Call, response: Response) {
                val reply =
                    try {
                        response.use { read(it, maxBytes, label) }
                    } catch (failed: SpotifyException) {
                        continuation.resumeWithException(failed)
                        return
                    } catch (failed: IOException) {
                        continuation.resumeWithException(SpotifyNetworkException(label, failed))
                        return
                    }
                continuation.resume(reply)
            }
        },
    )
}

private fun read(response: Response, maxBytes: Long, label: String): HttpReply {
    val body = response.body
    val declared = body.contentLength()
    if (declared > maxBytes) {
        throw SpotifyContentException("$label: a reply of $declared bytes, the limit is $maxBytes")
    }
    val source = body.source()
    if (source.request(maxBytes + 1)) {
        throw SpotifyContentException("$label: a reply of more than $maxBytes bytes")
    }
    val retryAfter = response.header(RETRY_AFTER)?.trim()?.toLongOrNull()
    return HttpReply(response.code, source.buffer.readByteArray(), retryAfter)
}

internal suspend fun OkHttpClient.postForm(
    base: String,
    path: String,
    form: List<Pair<String, String>>,
): FormReply {
    val body = FormBody.Builder()
    for ((key, value) in form) body.add(key, value)
    val request = Request.Builder().url((base + path).toHttpUrl()).post(body.build()).build()
    val reply = fetch(request, MAX_FORM_REPLY_BYTES)
    return FormReply(reply.status, jsonObjectOf(reply.body), path)
}

internal class FormReply(val status: Int, val json: JsonObject, val path: String) {
    fun text(key: String): String = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    fun int(key: String): Int? = (json[key] as? JsonPrimitive)?.intOrNull

    fun failure(): SpotifyException {
        val error = text(ERROR).take(MAX_ERROR_CHARS)
        return when {
            error.isNotEmpty() -> AuthorizationException(error, "POST $path")
            status !in 200..299 -> SpotifyHttpException(status, "POST", path)
            else -> SpotifyContentException("POST $path: a reply without the expected fields")
        }
    }

    companion object {
        const val ERROR = "error"
        private const val MAX_ERROR_CHARS = 64
    }
}

internal fun jsonObjectOf(body: ByteArray): JsonObject = try {
    Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
} catch (ignored: SerializationException) {
    null
} ?: JsonObject(emptyMap())

internal suspend fun <T> closingOnCancel(resource: Closeable, block: suspend CoroutineScope.() -> T): T =
    coroutineScope {
        val closer =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    closeQuietly(resource)
                }
            }
        try {
            block()
        } finally {
            closer.cancel()
        }
    }

internal fun closeQuietly(resource: Closeable) {
    try {
        resource.close()
    } catch (ignored: IOException) {
        // A socket that fails to close is closed as far as this app is concerned.
    }
}

internal const val RETRY_AFTER = "Retry-After"
internal const val MAX_FORM_REPLY_BYTES = 64L * 1024
