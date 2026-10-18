package io.github.kickoman.qiyaa.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call

@UnstableApi
class PlaybackDataSources(
    private val client: Call.Factory,
    private val links: TrackUrlCache,
    private val bitrate: CurrentBitrate,
    private val cache: PlaybackCache,
    private val interrupts: ReadInterrupts,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val watch = interrupts.watch()
        val calls = CallTracker(client, watch)
        val http = OkHttpDataSource.Factory(calls).setUserAgent(USER_AGENT).createDataSource()
        val network = InterruptibleDataSource(http, calls, watch)
        val signed = ExpiredLinkDataSource(ResolvingDataSource(network, TrackResolver(links, bitrate)), links)
        return cache.dataSource(signed)
    }

    companion object {
        const val USER_AGENT = "QiYaa/Android"
    }
}
