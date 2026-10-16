package be.digitalia.mediasession2mqtt.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import be.digitalia.mediasession2mqtt.inject.appGraph
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import dev.zacsweers.metro.Inject

class MediaSessionListenerService : NotificationListenerService() {
    @Inject
    lateinit var currentMediaControllerDetector: CurrentMediaControllerDetector

    override fun onCreate() {
        appGraph.inject(this)
        super.onCreate()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        currentMediaControllerDetector.startListening(ComponentName(this, this::class.java))
    }

    override fun onListenerDisconnected() {
        currentMediaControllerDetector.stopListening()
        super.onListenerDisconnected()
    }

    companion object {
        fun requestRebind(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(context, MediaSessionListenerService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }
}
