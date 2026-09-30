package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.jam.JamSocket
import io.github.kickoman.qiyaa.jam.JamSocketListener
import io.github.kickoman.qiyaa.jam.JamTransport

class FakeJamSocket(val url: String, private val listener: JamSocketListener) : JamSocket {
    val sent = mutableListOf<String>()
    var closedWith: Int? = null

    override fun send(text: String): Boolean {
        if (closedWith != null) return false
        sent += text
        return true
    }

    override fun close(code: Int) {
        closedWith = code
    }

    fun open() = listener.onOpen()

    fun receive(text: String) = listener.onMessage(text)

    fun drop() = listener.onClosed(1006)
}

class FakeJamTransport : JamTransport {
    val sockets = mutableListOf<FakeJamSocket>()
    val last: FakeJamSocket get() = sockets.last()

    override fun open(url: String, listener: JamSocketListener): JamSocket =
        FakeJamSocket(url, listener).also { sockets += it }
}
