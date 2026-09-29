package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.runBlocking

@UnstableApi
class TrackResolver(private val links: TrackUrlCache, private val bitrate: CurrentBitrate) :
    ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = MediaItems.trackIdOf(dataSpec.uri) ?: return dataSpec
        val resolved = runBlocking { links.get(id) }
        bitrate.onResolved(id, resolved.bitrateKbps)
        return dataSpec.buildUpon().setUri(Uri.parse(resolved.url)).build()
    }
}
