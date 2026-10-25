package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.kickoman.qiyaa.spotify.ReadGuard
import io.github.kickoman.qiyaa.spotify.SpotifyFiles
import io.github.kickoman.qiyaa.spotify.SpotifyStream
import io.github.kickoman.qiyaa.track.TrackSource
import kotlinx.coroutines.runBlocking
import okhttp3.Call

@UnstableApi
class SpotifyDataSource(
    private val calls: CallTracker,
    private val watch: ReadInterrupts.Watch,
    private val files: SpotifyFiles,
    private val bitrate: CurrentBitrate,
    private val log: (String) -> Unit = {},
) : BaseDataSource(true) {
    private var stream: SpotifyStream? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val trackId =
            requireNotNull(MediaItems.spotifyTrackIdOf(dataSpec.uri.schemeSpecificPart.orEmpty())) {
                "Not a Spotify track URI: ${dataSpec.uri}"
            }
        transferInitializing(dataSpec)
        val play = stream ?: firstOpen(trackId).also { stream = it }
        if (play.oggLength != SpotifyStream.UNKNOWN && dataSpec.position > play.oggLength) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val length = play.open(dataSpec.position, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = checkNotNull(stream).read(buffer, offset, length)
        if (count == SpotifyStream.END) return C.RESULT_END_OF_INPUT
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        uri = null
        stream?.close()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun firstOpen(trackId: String): SpotifyStream {
        val file = runBlocking { files.prepare(trackId) }
        bitrate.onResolved(MediaItems.mediaIdOf(TrackSource.SPOTIFY, trackId), file.kbps)
        return SpotifyStream(
            calls = calls,
            file = file,
            userAgent = files.userAgent,
            relink = { runBlocking { files.storageUrls(file) } },
            guard = WatchGuard(watch),
            report = files::report,
            log = log,
        )
    }
}

internal class WatchGuard(private val watch: ReadInterrupts.Watch) : ReadGuard {
    override fun <T> during(call: Call?, block: () -> T): T = watch.during(call, block)
}
