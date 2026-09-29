package io.github.kickoman.qiyaa.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.queue.QueueController

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var engine: Media3Engine? = null

    override fun onCreate() {
        super.onCreate()
        val graph = appGraph
        val renderers =
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(
                        arrayOf(
                            EqualizerProcessor(graph.audioBus),
                            VisualizerTapProcessor(graph.audioBus),
                        ),
                    )
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        val httpDataSource = OkHttpDataSource.Factory(graph.httpClient).setUserAgent(USER_AGENT)
        val dataSource = ResolvingDataSource.Factory(httpDataSource, TrackResolver(graph.api, graph.audioBus))
        val audioAttributes =
            AudioAttributes.Builder().setUsage(
                C.USAGE_MEDIA,
            ).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val player =
            ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
        player.volume = AudioBus.volumeGain(graph.settings.volume.value)
        engine = Media3Engine(player, graph.queue).also { it.attach() }

        val builder =
            MediaSession.Builder(this, QueueForwardingPlayer(player, graph.queue))
                .setCallback(ResumptionCallback(graph.queue))
        openAppIntent()?.let(builder::setSessionActivity)
        session = builder.build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, launch, flags)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            engine?.detach()
            it.player.release()
            it.release()
        }
        session = null
        engine = null
        super.onDestroy()
    }

    /**
     * "Play" from a headset or the system's resumption card when the player is empty: hand back the
     * saved queue, paused where it stopped (`QueueController` restored it from `data/QueueFile`).
     */
    private class ResumptionCallback(private val queue: QueueController) : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val point =
                queue.resumePoint()
                    ?: return Futures.immediateFailedFuture(
                        UnsupportedOperationException("The queue is empty"),
                    )
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    point.tracks.map(MediaItems::toMediaItem),
                    point.index,
                    point.positionMs,
                ),
            )
        }
    }

    companion object {
        const val USER_AGENT = "QiYaa/Android"
    }
}
