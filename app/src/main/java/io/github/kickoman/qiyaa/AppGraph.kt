package io.github.kickoman.qiyaa

import android.content.Context
import android.os.Build
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.data.Settings
import io.github.kickoman.qiyaa.data.TokenStore
import io.github.kickoman.qiyaa.playback.QueueManager
import io.github.kickoman.qiyaa.yandex.DeviceAuth
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.YandexApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph; the UI and the playback service share it (one process). */
class AppGraph(val context: Context) {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    val settings = Settings(context)
    val tokenStore = TokenStore(context)
    val api = YandexApi(httpClient).also { it.token = tokenStore.load() }
    val library = Library(api)
    val deviceAuth = DeviceAuth(httpClient, deviceName = Build.MODEL ?: "Android")
    val audioBus = AudioBus().also {
        it.eq.publish(settings.eq.value)
        it.setBalance(settings.balance.value)
    }
    val queue = QueueManager(library, api)
}
