package be.digitalia.mediasession2mqtt.inject

import android.content.Context

interface AppGraphProvider {
    val appGraph: AppGraph
}

val Context.appGraph: AppGraph
    get() = (applicationContext as AppGraphProvider).appGraph
