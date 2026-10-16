package be.digitalia.mediasession2mqtt.inject

import be.digitalia.mediasession2mqtt.mqtt.KMQTTClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

@BindingContainer
@ContributesTo(AppScope::class)
object MQTTPublishClientProviders {
    @Provides
    @SingleIn(AppScope::class)
    fun provideMQTTPublishClientFactory(): MQTTPublishClient.Factory {
        // Use a single thread per client because the KMQTT client is not fully thread safe
        return KMQTTClient.Factory(
            dispatcherProvider = { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
        )
    }
}
