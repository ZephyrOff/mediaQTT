package be.digitalia.mediasession2mqtt.mqtt

import be.digitalia.mediasession2mqtt.BuildConfig
import io.github.davidepianca98.MQTTClient
import io.github.davidepianca98.mqtt.MQTTVersion
import io.github.davidepianca98.mqtt.Subscription
import io.github.davidepianca98.mqtt.packets.Qos
import io.github.davidepianca98.mqtt.packets.mqttv5.ReasonCode
import io.github.davidepianca98.mqtt.packets.mqttv5.SubscriptionOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Keeps a long-lived MQTT connection subscribed to some topics and reports the received messages.
 *
 * Unlike [KMQTTClient], which opens a connection lazily for each publication, this class owns a dedicated
 * connection (and a dedicated thread, because the KMQTT client is not fully thread safe) for as long as
 * [collectMessages] is running.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class KMQTTSubscriber(private val connectionSettings: MQTTConnectionSettings) {

    /**
     * Subscribe to the topics and call [onMessage] for every received message until cancelled.
     * The connection is re-established automatically after any failure.
     *
     * [onMessage] is called from a background thread and must not block.
     *
     * Retained messages are ignored: the broker delivers them again after every reconnection, which would
     * replay stale commands. Only live messages are reported.
     */
    suspend fun collectMessages(
        qosLevel: MQTTQoSLevel,
        topics: List<String>,
        onMessage: (topic: String, payload: String) -> Unit
    ) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            while (true) {
                try {
                    withContext(dispatcher) {
                        runSession(qosLevel, topics, onMessage)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Connection failed or was lost: retry below
                }
                delay(RETRY_DELAY_MILLIS)
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Connect, subscribe and process incoming messages until cancelled or disconnected.
     * Runs on the dedicated thread. Always throws when the connection is lost, so that the caller retries.
     */
    private fun CoroutineScope.runSession(
        qosLevel: MQTTQoSLevel,
        topics: List<String>,
        onMessage: (topic: String, payload: String) -> Unit
    ) {
        val client = createClient(onMessage)
        try {
            val options = SubscriptionOptions(qos = Qos.entries[qosLevel.ordinal])
            // The request is queued by the client until the CONNACK packet has been received
            client.subscribe(topics.map { Subscription(it, options) })
            // Each step blocks for a short read timeout, which is also the delay to react to cancellation
            while (isActive && client.isRunning()) {
                client.step()
            }
        } finally {
            if (client.isRunning()) {
                try {
                    client.disconnect(ReasonCode.SUCCESS)
                } catch (_: Exception) {
                }
            }
        }
        if (isActive) {
            throw IOException("MQTT connection closed")
        }
    }

    private fun createClient(onMessage: (topic: String, payload: String) -> Unit): MQTTClient {
        val mqttVersion = when (connectionSettings.protocolVersion) {
            MQTTConnectionSettings.ProtocolVersion.MQTT3_1_1 -> MQTTVersion.MQTT3_1_1
            MQTTConnectionSettings.ProtocolVersion.MQTT5 -> MQTTVersion.MQTT5
        }
        val authentication = connectionSettings.authentication
        return MQTTClient(
            mqttVersion = mqttVersion,
            address = connectionSettings.hostname,
            port = connectionSettings.port,
            tls = null,
            // Unlike publishing connections, this one lives long: let the broker detect a dead client
            // and let a dead socket surface as a failed ping
            keepAlive = KEEP_ALIVE_SECONDS,
            webSocket = null,
            userName = authentication?.username,
            password = authentication?.password?.encodeToByteArray()?.toUByteArray(),
            connackTimeout = 10,
            connectTimeout = 10,
            debugLog = BuildConfig.DEBUG,
        ) { publish ->
            if (!publish.retain) {
                val payload = publish.payload?.toByteArray()?.decodeToString().orEmpty()
                onMessage(publish.topicName, payload)
            }
        }
    }

    private companion object {
        private const val KEEP_ALIVE_SECONDS = 30
        private const val RETRY_DELAY_MILLIS = 5000L
    }
}
