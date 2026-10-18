package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.Call

@UnstableApi
class InterruptibleDataSource(
    private val upstream: DataSource,
    private val calls: CallTracker,
    private val watch: ReadInterrupts.Watch,
) : DataSource {
    private var call: Call? = null

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        call = null
        val length = watch.during(null) { upstream.open(dataSpec) }
        call = calls.takeLast()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        watch.during(call) { upstream.read(buffer, offset, length) }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        call = null
        upstream.close()
    }
}
