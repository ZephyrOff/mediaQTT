package be.digitalia.mediasession2mqtt.inject

import android.app.Application
import android.content.Context
import be.digitalia.mediasession2mqtt.MainWorker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides

@DependencyGraph(AppScope::class)
interface AppGraph : AndroidInjectors {
    val mainWorker: MainWorker

    @Binds
    val Application.bind: Context

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
