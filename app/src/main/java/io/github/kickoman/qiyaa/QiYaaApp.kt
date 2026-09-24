package io.github.kickoman.qiyaa

import android.app.Application

class QiYaaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as QiYaaApp).graph
