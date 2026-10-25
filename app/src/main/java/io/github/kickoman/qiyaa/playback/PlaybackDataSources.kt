package io.github.kickoman.qiyaa.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.kickoman.qiyaa.spotify.SpotifyFiles
import okhttp3.Call

@UnstableApi
class PlaybackDataSources(
    private val context: Context,
    private val client: Call.Factory,
    private val links: TrackUrlCache,
    private val spotifyFiles: SpotifyFiles,
    private val bitrate: CurrentBitrate,
    private val cache: PlaybackCache,
    private val interrupts: ReadInterrupts,
    private val log: (String) -> Unit = {},
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val watch = interrupts.watch()
        val calls = CallTracker(client, watch)
        val http = OkHttpDataSource.Factory(calls).setUserAgent(USER_AGENT).createDataSource()
        val network = InterruptibleDataSource(http, calls, watch)
        val signed = ExpiredLinkDataSource(ResolvingDataSource(network, TrackResolver(links, bitrate)), links)
        val spotify = SpotifyDataSource(calls, watch, spotifyFiles, bitrate, log)
        return DefaultDataSource(context, cache.dataSource(SchemeRoute(spotify, signed)))
    }

    companion object {
        const val USER_AGENT = "QiYaa/Android"
    }
}
