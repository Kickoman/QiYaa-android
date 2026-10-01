package io.github.kickoman.qiyaa

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.data.JamFile
import io.github.kickoman.qiyaa.data.QueueFile
import io.github.kickoman.qiyaa.data.Settings
import io.github.kickoman.qiyaa.data.TokenStore
import io.github.kickoman.qiyaa.jam.JamSessionStore
import io.github.kickoman.qiyaa.jam.JamStore
import io.github.kickoman.qiyaa.playback.JamCatalog
import io.github.kickoman.qiyaa.playback.JamHost
import io.github.kickoman.qiyaa.playback.JamHostConfig
import io.github.kickoman.qiyaa.playback.OkHttpJamTransport
import io.github.kickoman.qiyaa.queue.LibraryMusicSource
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.QueueStore
import io.github.kickoman.qiyaa.yandex.DeviceAuth
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.Session
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.YandexApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppGraph(val context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    val settings = Settings(context, defaultJamServer = BuildConfig.JAM_URL)
    val tokenStore = TokenStore(context)
    val api = YandexApi(httpClient).also { it.token = tokenStore.load() }
    val library = Library(api)
    val networkMonitor = NetworkMonitor(context, applicationScope)
    val session = Session(library, networkMonitor.available, applicationScope).also { it.start() }
    val deviceAuth = DeviceAuth(httpClient, deviceName = Build.MODEL ?: "Android")
    val audioBus =
        AudioBus().also { bus ->
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                settings.eq.collect(bus.equalizer::publish)
            }
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                settings.balance.collect(bus::setBalance)
            }
        }
    val queue =
        QueueController(
            source = LibraryMusicSource(library),
            connectivity = networkMonitor.available,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            io = Dispatchers.IO,
            store = QueueFile(context).asQueueStore(),
            logFailure = { stage, failed -> Log.w(LOG_TAG, "Queue $stage failed", failed) },
        )
    val jamHost =
        JamHost(
            transport = OkHttpJamTransport(httpClient),
            connectivity = networkMonitor.available,
            queue = queue,
            catalog = library.asJamCatalog(),
            store = JamSessionStore(JamFile(context).asJamStore()),
            config = {
                JamHostConfig(settings.jamServer.value, settings.jamWaveFeedback.value)
            },
            queueTitle = { context.getString(R.string.jam_queue_title) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            io = Dispatchers.IO,
            appVersion = BuildConfig.VERSION_NAME,
            log = { message, failed -> Log.w(LOG_TAG, message, failed) },
        )

    companion object {
        const val HTTP_TIMEOUT_SECONDS = 20L
        const val LOG_TAG = "QiYaa"

        private fun JamFile.asJamStore(): JamStore = object : JamStore {
            override fun read(): String? = this@asJamStore.read()

            override fun write(text: String?) = this@asJamStore.write(text)
        }

        private fun Library.asJamCatalog(): JamCatalog = object : JamCatalog {
            override suspend fun searchTracks(text: String): List<Track> =
                this@asJamCatalog.searchTracks(text)

            override suspend fun tracks(ids: List<String>): List<Track> = tracksByIds(ids)
        }

        private fun QueueFile.asQueueStore(): QueueStore = object : QueueStore {
            override fun read(): String? = this@asQueueStore.read()

            override fun write(text: String) = this@asQueueStore.write(text)
        }
    }
}
