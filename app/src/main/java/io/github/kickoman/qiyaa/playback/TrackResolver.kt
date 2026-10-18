package io.github.kickoman.qiyaa.playback

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.runBlocking

@UnstableApi
class TrackResolver(links: TrackUrlCache, bitrate: CurrentBitrate) : ResolvingDataSource.Resolver {
    private val play = PlayLink(links, bitrate)

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = MediaItems.trackIdOf(dataSpec.uri) ?: return dataSpec
        val url = runBlocking { play.url(id) }
        return dataSpec.buildUpon().setUri(url.toUri()).build()
    }
}
