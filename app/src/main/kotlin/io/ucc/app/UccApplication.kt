package io.ucc.app

import android.app.Application
import io.ucc.app.di.AppGraph

class UccApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    companion object {
        fun graph(context: android.content.Context): AppGraph = (context.applicationContext as UccApplication).graph
    }
}
