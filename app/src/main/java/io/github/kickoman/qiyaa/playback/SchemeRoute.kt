package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

@UnstableApi
class SchemeRoute(private val spotify: DataSource, private val other: DataSource) : DataSource {
    private var opened: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        spotify.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.scheme == MediaItems.SPOTIFY_SCHEME) spotify else other
        opened = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(opened) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = opened?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = opened?.responseHeaders ?: emptyMap()

    override fun close() {
        val target = opened ?: return
        opened = null
        target.close()
    }
}
