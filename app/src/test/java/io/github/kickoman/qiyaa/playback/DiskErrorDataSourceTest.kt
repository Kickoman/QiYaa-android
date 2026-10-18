package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.kickoman.qiyaa.yandex.NetworkException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DiskErrorDataSourceTest {
    private val spec = anyDataSpec()
    private val logged = mutableListOf<String>()

    @Test
    fun `TR-09 ERR-04 a full disk never makes a track unplayable, open, read and close drop the cause`() {
        for (failure in diskFailures()) {
            for (operation in Operation.entries) {
                val thrown = thrownBy(operation, failure)

                assertEquals("$operation $failure", Cache.CacheException::class.java, thrown.javaClass)
                assertNull(thrown.cause)
                assertEquals("The disk failed for $KEY: $failure", thrown.message)
                assertEquals(thrown.message, logged.last())
            }
        }
    }

    @Test
    fun `TR-09 ERR-04 a full disk never makes a track unplayable, the loader retries at once`() {
        val policy = DefaultLoadErrorHandlingPolicy()
        for (failure in diskFailures()) {
            assertEquals("$failure", C.TIME_UNSET, policy.getRetryDelayMsFor(firstError(failure)))
            for (operation in Operation.entries) {
                assertEquals(0L, policy.getRetryDelayMsFor(firstError(thrownBy(operation, failure))))
            }
        }
    }

    @Test
    fun `network failures pass through unchanged and are not logged`() {
        val failures =
            listOf(
                HttpDataSource.HttpDataSourceException.createForIOException(
                    SocketTimeoutException("timeout"),
                    spec,
                    HttpDataSource.HttpDataSourceException.TYPE_READ,
                ),
                NetworkException("GET", "/tracks/1/download-info", IOException("offline")),
                InterruptedIOException(),
                IOException("Socket closed"),
            )
        for (failure in failures) {
            for (operation in Operation.entries) {
                assertSame("$operation $failure", failure, thrownBy(operation, failure))
            }
        }
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `lengths, reads and response headers come from the wrapped source`() {
        val source = DiskErrorDataSource(Wrapped(failing = null, failure = null), KEY, logged::add)

        assertEquals(LENGTH, source.open(spec))
        assertEquals(3, source.read(ByteArray(8), 0, 3))
        assertEquals(HEADERS, source.responseHeaders)
        source.close()
    }

    private fun diskFailures(): List<IOException> {
        val spanFile = "$FOLDER/3/1.0.1760000000000.v3.exo"
        val enospc = FileNotFoundException("$spanFile: open failed: ENOSPC (No space left on device)")
        return listOf(
            CacheDataSink.CacheDataSinkException(enospc),
            Cache.CacheException(IOException("Couldn't create $FOLDER/cached_content_index.exi", enospc)),
            FileDataSource.FileDataSourceException(
                FileNotFoundException("$spanFile: open failed: ENOENT (No such file or directory)"),
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            ),
            FileDataSource.FileDataSourceException(
                null,
                null,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            ),
        )
    }

    private fun thrownBy(operation: Operation, failure: IOException): IOException {
        val source = DiskErrorDataSource(Wrapped(operation, failure), KEY, logged::add)
        return assertThrows(IOException::class.java) {
            when (operation) {
                Operation.OPEN -> source.open(spec)
                Operation.READ -> source.read(ByteArray(8), 0, 8)
                Operation.CLOSE -> source.close()
            }
        }
    }

    private fun firstError(failure: IOException) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo.Builder(1, spec, 0).build(),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        failure,
        1,
    )

    // DataSpec rejects a null Uri, and android.net.Uri is a stub on the JVM; nothing here reads the spec.
    private fun anyDataSpec(): DataSpec {
        val unsafe = Class.forName("sun.misc.Unsafe")
        val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafe.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(instance, DataSpec::class.java) as DataSpec
    }

    private enum class Operation { OPEN, READ, CLOSE }

    private class Wrapped(private val failing: Operation?, private val failure: IOException?) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            failIf(Operation.OPEN)
            return LENGTH
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            failIf(Operation.READ)
            return length
        }

        override fun getUri(): Uri? = null

        override fun getResponseHeaders(): Map<String, List<String>> = HEADERS

        override fun close() = failIf(Operation.CLOSE)

        private fun failIf(operation: Operation) {
            if (operation == failing) throw checkNotNull(failure)
        }
    }

    private companion object {
        const val KEY = "play-0000abcd-7"
        const val FOLDER = "/data/user/0/io.github.kickoman.qiyaa/cache/playback"
        const val LENGTH = 4_194_304L
        val HEADERS = mapOf("ETag" to listOf("\"5f3a\""))
    }
}
