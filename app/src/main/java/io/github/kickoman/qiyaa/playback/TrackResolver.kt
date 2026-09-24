package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.yandex.YandexApi
import kotlinx.coroutines.runBlocking

/**
 * Resolves `qiyaa://track/{id}` to the signed storage URL right before ExoPlayer opens the
 * stream. Runs on ExoPlayer's loading thread, so a blocking call is fine here.
 */
@UnstableApi
class TrackResolver(private val api: YandexApi, private val bus: AudioBus) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = MediaItems.trackIdOf(dataSpec.uri) ?: return dataSpec
        val resolved = runBlocking { api.resolveTrackUrl(id) }
        bus.setBitrate(resolved.bitrateKbps)
        return dataSpec.buildUpon().setUri(Uri.parse(resolved.url)).build()
    }
}
