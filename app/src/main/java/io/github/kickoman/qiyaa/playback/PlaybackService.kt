package io.github.kickoman.qiyaa.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.kickoman.qiyaa.AppLocale
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.yandex.Library
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var engine: Media3Engine? = null
    private val serviceScope = MainScope()
    private val currentTrackId = MutableStateFlow<String?>(null)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.apply(this, appGraph.settings.language.value)
    }

    override fun onCreate() {
        super.onCreate()
        val graph = appGraph
        val renderers =
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = TimedAudioSink(
                    DefaultAudioSink.Builder(context)
                        .setAudioProcessors(
                            arrayOf(
                                EqualizerProcessor(graph.audioBus),
                                VisualizerTapProcessor(graph.audioBus),
                            ),
                        )
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build(),
                    graph.audioBus.visualizerTap,
                )
            }
        val links =
            TrackUrlCache(clock = SystemClock::elapsedRealtime) { id ->
                Log.d(LOG_TAG, "Signing the link of track $id")
                graph.api.resolveTrackUrl(id)
            }
        val bitrate = CurrentBitrate(graph.audioBus::setBitrate)
        val httpDataSource = OkHttpDataSource.Factory(graph.httpClient).setUserAgent(USER_AGENT)
        val dataSource =
            ExpiredLinkDataSource.Factory(
                ResolvingDataSource.Factory(httpDataSource, TrackResolver(links, bitrate)),
                links,
            )
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
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.settings.volume.collect { player.volume = AudioBus.volumeGain(it) }
        }
        engine = Media3Engine(player, graph.queue).also { it.attach() }
        graph.jamHost.onServiceStarted()

        val builder =
            MediaSession.Builder(this, QueueForwardingPlayer(player, graph.queue))
                .setCallback(SessionCallback(this, graph.queue, graph.library))
        openAppIntent()?.let(builder::setSessionActivity)
        val built = builder.build()
        session = built
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    currentTrackId.value = mediaItem?.mediaId
                }
            },
        )
        currentTrackId.value = player.currentMediaItem?.mediaId
        serviceScope.launch { currentTrackId.collect(bitrate::onCurrentChanged) }
        serviceScope.launch {
            // The buttons' names follow the language picked while the service runs.
            combine(currentTrackId, graph.library.likedIds, graph.settings.language) { id, liked, language ->
                (id != null && id in liked) to language
            }
                .distinctUntilChanged()
                .collect { (liked, language) ->
                    AppLocale.apply(this@PlaybackService, language)
                    built.setCustomLayout(NotificationButtons.layout(this@PlaybackService, liked))
                }
        }
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
        appGraph.jamHost.onServiceStopped()
        serviceScope.cancel()
        session?.let {
            engine?.detach()
            it.player.release()
            it.release()
        }
        session = null
        engine = null
        super.onDestroy()
    }

    private class SessionCallback(
        private val context: Context,
        private val queue: QueueController,
        private val library: Library,
    ) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val current = session.player.currentMediaItem?.mediaId
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    NotificationButtons.withCustomCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                    ),
                )
                .setCustomLayout(
                    NotificationButtons.layout(
                        context,
                        current != null && library.isLiked(current),
                    ),
                )
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                NotificationButtons.ACTION_LIKE -> queue.likeCurrent()
                NotificationButtons.ACTION_DISLIKE -> queue.dislikeCurrent()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

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
