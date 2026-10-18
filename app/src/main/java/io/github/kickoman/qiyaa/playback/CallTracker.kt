package io.github.kickoman.qiyaa.playback

import okhttp3.Call
import okhttp3.Request

class CallTracker(private val client: Call.Factory, private val watch: ReadInterrupts.Watch) : Call.Factory {
    @Volatile
    private var last: Call? = null

    override fun newCall(request: Request): Call = client.newCall(request).also { call ->
        last = call
        watch.bind(call)
    }

    fun takeLast(): Call? = last.also { last = null }
}
