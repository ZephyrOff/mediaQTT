package be.digitalia.mediasession2mqtt

import android.app.Application
import be.digitalia.mediasession2mqtt.inject.AppGraph
import be.digitalia.mediasession2mqtt.inject.AppGraphProvider
import dev.zacsweers.metro.createGraphFactory

class MainApplication : Application(), AppGraphProvider {

    override val appGraph: AppGraph by lazy(LazyThreadSafetyMode.NONE) {
        createGraphFactory<AppGraph.Factory>().create(this)
    }

    override fun onCreate() {
        super.onCreate()

        appGraph.mainWorker.start()
    }
}
