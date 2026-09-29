package io.github.kickoman.qiyaa

import android.content.Context
import android.os.Build
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.data.Settings
import io.github.kickoman.qiyaa.data.TokenStore
import io.github.kickoman.qiyaa.playback.QueueManager
import io.github.kickoman.qiyaa.yandex.DeviceAuth
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.Session
import io.github.kickoman.qiyaa.yandex.YandexApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppGraph(val context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    val settings = Settings(context)
    val tokenStore = TokenStore(context)
    val api = YandexApi(httpClient).also { it.token = tokenStore.load() }
    val library = Library(api)
    val networkMonitor = NetworkMonitor(context, applicationScope)
    val session = Session(library, networkMonitor.available, applicationScope).also { it.start() }
    val deviceAuth = DeviceAuth(httpClient, deviceName = Build.MODEL ?: "Android")
    val audioBus =
        AudioBus().also {
            it.equalizer.publish(settings.eq.value)
            it.setBalance(settings.balance.value)
        }
    val queue = QueueManager(library, api)

    companion object {
        const val HTTP_TIMEOUT_SECONDS = 20L
    }
}
