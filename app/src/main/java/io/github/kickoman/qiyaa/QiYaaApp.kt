package io.github.kickoman.qiyaa

import android.app.Application
import android.content.Context
import android.content.res.Configuration

class QiYaaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A system change (the device's language, the screen) resets the resources' locale.
        if (::graph.isInitialized) AppLocale.apply(this, graph.settings.language.value)
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as QiYaaApp).graph
