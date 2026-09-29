package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

@UnstableApi
class ExpiredLinkDataSource(private val upstream: DataSource, private val links: TrackUrlCache) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val trackId = MediaItems.trackIdOf(dataSpec.uri)
        return try {
            upstream.open(dataSpec)
        } catch (failed: HttpDataSource.InvalidResponseCodeException) {
            if (trackId == null || failed.responseCode !in EXPIRED_LINK_STATUSES) throw failed
            upstream.close()
            links.invalidate(trackId)
            upstream.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory, private val links: TrackUrlCache) :
        DataSource.Factory {
        override fun createDataSource(): DataSource =
            ExpiredLinkDataSource(upstream.createDataSource(), links)
    }

    companion object {
        val EXPIRED_LINK_STATUSES = setOf(403, 410)
    }
}
