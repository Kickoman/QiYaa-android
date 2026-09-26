package io.github.kickoman.qiyaa.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.yandex.YandexApi
import kotlinx.coroutines.runBlocking

@UnstableApi
class TrackResolver(private val api: YandexApi, private val audioBus: AudioBus) :
    ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = MediaItems.trackIdOf(dataSpec.uri) ?: return dataSpec
        val resolved = runBlocking { api.resolveTrackUrl(id) }
        audioBus.setBitrate(resolved.bitrateKbps)
        return dataSpec.buildUpon().setUri(Uri.parse(resolved.url)).build()
    }
}
