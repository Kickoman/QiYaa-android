package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import java.io.IOException

@UnstableApi
class DiskErrorDataSource(
    private val upstream: DataSource,
    private val key: String,
    private val log: (String) -> Unit = {},
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = retriable { upstream.open(dataSpec) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        retriable { upstream.read(buffer, offset, length) }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = retriable { upstream.close() }

    private inline fun <T> retriable(block: () -> T): T = try {
        block()
    } catch (failed: Cache.CacheException) {
        throw withoutCause(failed)
    } catch (failed: FileDataSource.FileDataSourceException) {
        throw withoutCause(failed)
    }

    private fun withoutCause(failed: IOException): Cache.CacheException {
        val message = "The disk failed for $key: $failed"
        log(message)
        return Cache.CacheException(message)
    }
}
