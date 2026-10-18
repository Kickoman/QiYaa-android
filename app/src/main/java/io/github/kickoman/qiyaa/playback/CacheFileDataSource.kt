package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache

@UnstableApi
class CacheFileDataSource(private val log: (String) -> Unit = {}) : DataSource {
    private val file = FileDataSource()

    override fun addTransferListener(transferListener: TransferListener) =
        file.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val byte = dataSpec.uriPositionOffset + dataSpec.position
        val length =
            try {
                file.open(dataSpec)
            } catch (failed: FileDataSource.FileDataSourceException) {
                throw Cache.CacheException(
                    "The disk copy of ${dataSpec.key} at byte $byte did not open: $failed",
                )
            }
        log("Reading ${dataSpec.key} from the disk at byte $byte")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = file.read(buffer, offset, length)

    override fun getUri(): Uri? = file.uri

    override fun getResponseHeaders(): Map<String, List<String>> = file.responseHeaders

    override fun close() = file.close()
}
