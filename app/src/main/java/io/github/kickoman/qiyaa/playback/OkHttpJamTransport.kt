package io.github.kickoman.qiyaa.playback

import io.github.kickoman.qiyaa.jam.JamSocket
import io.github.kickoman.qiyaa.jam.JamSocketListener
import io.github.kickoman.qiyaa.jam.JamTransport
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class OkHttpJamTransport(httpClient: OkHttpClient) : JamTransport {
    private val client =
        httpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .build()

    override fun open(url: String, listener: JamSocketListener): JamSocket {
        var reported = false
        fun closedOnce(code: Int) {
            if (!reported) {
                reported = true
                listener.onClosed(code)
            }
        }
        val webSocket =
            client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

                    override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        closedOnce(code)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closedOnce(code)

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                        closedOnce(ABNORMAL_CLOSE)
                },
            )
        return object : JamSocket {
            override fun send(text: String): Boolean = webSocket.send(text)

            override fun close(code: Int) {
                webSocket.close(code, null)
            }
        }
    }

    companion object {
        const val PING_SECONDS = 20L
        const val ABNORMAL_CLOSE = 1006
    }
}
