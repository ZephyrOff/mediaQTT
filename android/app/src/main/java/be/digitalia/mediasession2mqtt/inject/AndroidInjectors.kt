package be.digitalia.mediasession2mqtt.inject

import be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
import be.digitalia.mediasession2mqtt.ui.SettingsActivity

interface AndroidInjectors {
    fun inject(settingsActivity: SettingsActivity)
    fun inject(mediaSessionListenerService: MediaSessionListenerService)
}
