package io.github.kickoman.qiyaa

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.data.QueueFile
import io.github.kickoman.qiyaa.data.Settings
import io.github.kickoman.qiyaa.data.TokenStore
import io.github.kickoman.qiyaa.queue.LibraryMusicSource
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.QueueStore
import io.github.kickoman.qiyaa.yandex.DeviceAuth
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.Session
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

    companion object {
        const val HTTP_TIMEOUT_SECONDS = 20L
        const val LOG_TAG = "QiYaa"

        private fun QueueFile.asQueueStore(): QueueStore = object : QueueStore {
            override fun read(): String? = this@asQueueStore.read()

            override fun write(text: String) = this@asQueueStore.write(text)
        }
    }
}
