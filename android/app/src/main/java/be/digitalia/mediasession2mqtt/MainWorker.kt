package be.digitalia.mediasession2mqtt

import android.content.Context
import android.media.session.PlaybackState
import be.digitalia.mediasession2mqtt.connectivity.ConnectivityChecker
import be.digitalia.mediasession2mqtt.homeassistant.Button
import be.digitalia.mediasession2mqtt.homeassistant.Sensor
import be.digitalia.mediasession2mqtt.homeassistant.createButtonDiscoveryConfiguration
import be.digitalia.mediasession2mqtt.homeassistant.createSensorDiscoveryConfiguration
import be.digitalia.mediasession2mqtt.localapi.LocalApiService
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.MediaCommand
import be.digitalia.mediasession2mqtt.mediasession.execute
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import be.digitalia.mediasession2mqtt.mediasession.parseMediaCommand
import be.digitalia.mediasession2mqtt.mediasession.parseSeekCommand
import be.digitalia.mediasession2mqtt.mediasession.playbackStateFlow
import be.digitalia.mediasession2mqtt.mqtt.KMQTTSubscriber
import be.digitalia.mediasession2mqtt.mqtt.MQTTConnectionSettings
import be.digitalia.mediasession2mqtt.mqtt.MQTTPublishClient
import be.digitalia.mediasession2mqtt.mqtt.MQTTQoSLevel
import be.digitalia.mediasession2mqtt.mqtt.tryConnectAndPublish
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTMediaMetadata
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.getPlayingPositionDrift
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMQTTPlaybackStateOrNull
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaDurationInMillis
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaTitle
import be.digitalia.mediasession2mqtt.service.MediaSessionListenerService
import be.digitalia.mediasession2mqtt.settings.SettingsProvider
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

@Inject
class MainWorker(
    private val context: Context,
    private val currentMediaControllerDetector: CurrentMediaControllerDetector,
    private val connectivityChecker: ConnectivityChecker,
    private val settingsProvider: SettingsProvider,
    private val mqttClientFactory: MQTTPublishClient.Factory,
    private val localApiService: LocalApiService,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val applicationIdFlow: Flow<String> =
        currentMediaControllerDetector.currentMediaController.map { mediaController ->
            mediaController?.packageName.orEmpty()
        }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackStateFlow: Flow<MQTTPlaybackState> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(MQTTPlaybackState.Idle)
                else -> mediaController.playbackStateFlow
                    .distinctUntilChanged { old, new ->
                        // Only report playback state changes or drifting positions while playing
                        val oldState = old?.state ?: PlaybackState.STATE_NONE
                        val newState = new?.state ?: PlaybackState.STATE_NONE
                        oldState == newState && abs(getPlayingPositionDrift(old, new)) < POSITION_DRIFT_THRESHOLD_MILLIS
                    }
                    .mapNotNull { it?.toMQTTPlaybackStateOrNull() }
            }
        }.buffer(Channel.RENDEZVOUS)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mediaMetadataFlow: Flow<MQTTMediaMetadata> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(MQTTMediaMetadata())
                else -> mediaController.metadataFlow
                    .map {
                        MQTTMediaMetadata(
                            title = it.toMediaTitle(),
                            durationInMillis = it.toMediaDurationInMillis()
                        )
                    }
            }
        }.buffer(Channel.RENDEZVOUS)

    private suspend fun monitorSettings() {
        // The publishing coroutine is only active when settings are valid and the active network is connected
        settingsProvider.connectionSettings
            .combine(connectivityChecker.isActiveNetworkConnectedFlow) { connectionSettings, isConnected ->
                if (isConnected) connectionSettings else null
            }
            .collectLatest { connectionSettings ->
                if (connectionSettings != null) {
                    val client = mqttClientFactory.create(connectionSettings)
                    try {
                        settingsProvider.messageSettings.collectLatest { (qosLevel, deviceId) ->
                            coroutineScope {
                                launch { publishHassConfigurationIfEnabled(client, qosLevel, deviceId) }
                                launch { publishApplicationId(client, qosLevel, deviceId) }
                                launch { publishPlaybackState(client, qosLevel, deviceId) }
                                launch { publishMediaMetadata(client, qosLevel, deviceId) }
                                launch { listenToCommands(connectionSettings, qosLevel, deviceId) }
                            }
                        }
                    } finally {
                        client.disconnectQuietly()
                    }
                }
            }
    }

    private suspend fun publishHassConfigurationIfEnabled(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        settingsProvider.isHassIntegrationEnabled
            .combine(settingsProvider.isCommandsEnabled) { isHassEnabled, isCommandsEnabled ->
                isHassEnabled to isCommandsEnabled
            }
            .collectLatest { (isHassEnabled, isCommandsEnabled) ->
                if (isHassEnabled) {
                    for (sensor in HASS_SENSORS) {
                        val discoveryConfig = createSensorDiscoveryConfiguration(
                            deviceId = deviceId,
                            sensor = sensor,
                            sensorTopic = "$ROOT_TOPIC/$deviceId/${sensor.subTopic}"
                        )
                        client.tryConnectAndPublish(
                            qosLevel,
                            "$HASS_ROOT_TOPIC/${sensor.type}/${sensor.getUniqueId(deviceId)}/config",
                            discoveryConfig
                        )
                    }
                    for (button in HASS_BUTTONS) {
                        // An empty retained payload removes the entity from Home Assistant
                        // when commands are disabled
                        val discoveryConfig = if (isCommandsEnabled) {
                            createButtonDiscoveryConfiguration(
                                deviceId = deviceId,
                                button = button,
                                commandTopic = "$ROOT_TOPIC/$deviceId/$COMMAND_SUB_TOPIC"
                            )
                        } else {
                            ""
                        }
                        client.tryConnectAndPublish(
                            qosLevel,
                            "$HASS_ROOT_TOPIC/${button.type}/${button.getUniqueId(deviceId)}/config",
                            discoveryConfig
                        )
                    }
                }
            }
    }

    private suspend fun listenToCommands(
        connectionSettings: MQTTConnectionSettings,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        settingsProvider.isCommandsEnabled.collectLatest { isEnabled ->
            if (isEnabled) {
                val commandTopic = "$ROOT_TOPIC/$deviceId/$COMMAND_SUB_TOPIC"
                val seekTopic = "$ROOT_TOPIC/$deviceId/$SEEK_SUB_TOPIC"
                // Messages arrive on the MQTT thread and are applied in order on the main thread
                val commands = Channel<MediaCommand>(Channel.UNLIMITED)
                coroutineScope {
                    launch {
                        KMQTTSubscriber(connectionSettings).collectMessages(
                            qosLevel,
                            listOf(commandTopic, seekTopic)
                        ) { topic, payload ->
                            val command = if (topic == seekTopic) parseSeekCommand(payload) else parseMediaCommand(payload)
                            if (command != null) {
                                commands.trySend(command)
                            }
                        }
                    }
                    launch {
                        for (command in commands) {
                            try {
                                currentMediaControllerDetector.currentMediaController.value?.execute(command)
                            } catch (_: Exception) {
                                // The target application may have released its session in the meantime
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun publishApplicationId(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        applicationIdFlow.collect { applicationId ->
            client.tryConnectAndPublish(
                qosLevel,
                "$ROOT_TOPIC/$deviceId/$APPLICATION_ID_SUB_TOPIC",
                applicationId
            )
        }
    }

    private suspend fun publishPlaybackState(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        playbackStateFlow.fold(null as MQTTPlaybackState?) { previousPlaybackState, playbackState ->
            val name = playbackState.name
            if (previousPlaybackState?.name != name) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$PLAYBACK_STATE_SUB_TOPIC",
                    name
                )
            }
            val positionInMillis = playbackState.positionInMillis
            if (previousPlaybackState?.positionInMillis != positionInMillis) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$PLAYBACK_POSITION_SUB_TOPIC",
                    if (positionInMillis < 0) "" else positionInMillis.toString()
                )
            }
            playbackState
        }
    }

    private suspend fun publishMediaMetadata(
        client: MQTTPublishClient,
        qosLevel: MQTTQoSLevel,
        deviceId: Int
    ) {
        mediaMetadataFlow.fold(null as MQTTMediaMetadata?) { previousMediaMetadata, mediaMetadata ->
            val title = mediaMetadata.title
            if (previousMediaMetadata?.title != title) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$MEDIA_TITLE_SUB_TOPIC",
                    title
                )
            }
            val durationInMillis = mediaMetadata.durationInMillis
            if (previousMediaMetadata?.durationInMillis != durationInMillis) {
                client.tryConnectAndPublish(
                    qosLevel,
                    "$ROOT_TOPIC/$deviceId/$MEDIA_DURATION_SUB_TOPIC",
                    durationInMillis
                )
            }
            mediaMetadata
        }
    }

    fun start() {
        coroutineScope.launch {
            monitorSettings()
        }
        coroutineScope.launch {
            // Does nothing unless enabled in the settings
            localApiService.run()
        }
        // Watchdog to attempt rebinding MediaSessionListenerService when disconnected
        coroutineScope.launch {
            currentMediaControllerDetector.isListening.collectLatest { isListening ->
                if (!isListening) {
                    delay(AUTO_REBIND_SERVICE_DELAY_MILLIS)
                    MediaSessionListenerService.requestRebind(context)
                }
            }
        }
    }

    companion object {
        private const val POSITION_DRIFT_THRESHOLD_MILLIS = 1000L
        private const val AUTO_REBIND_SERVICE_DELAY_MILLIS = 2000L

        private const val ROOT_TOPIC = "mediaSession"
        private const val APPLICATION_ID_SUB_TOPIC = "applicationId"
        private const val PLAYBACK_STATE_SUB_TOPIC = "playbackState"
        private const val PLAYBACK_POSITION_SUB_TOPIC = "playbackPosition"
        private const val MEDIA_TITLE_SUB_TOPIC = "mediaTitle"
        private const val MEDIA_DURATION_SUB_TOPIC = "mediaDuration"
        private const val COMMAND_SUB_TOPIC = "command"
        private const val SEEK_SUB_TOPIC = "seek"

        private const val HASS_ROOT_TOPIC = "homeassistant"
        private val HASS_SENSORS = listOf(
            Sensor(
                name = "Playback State",
                serializedName = "playback_state",
                icon = "mdi:play-pause",
                subTopic = PLAYBACK_STATE_SUB_TOPIC
            ),
            Sensor(
                name = "Playback Position",
                serializedName = "playback_position",
                icon = "mdi:progress-clock",
                subTopic = PLAYBACK_POSITION_SUB_TOPIC,
                deviceClass = "duration",
                unitOfMeasurement = "ms"
            ),
            Sensor(
                name = "Application Id",
                serializedName = "application_id",
                icon = "mdi:application",
                subTopic = APPLICATION_ID_SUB_TOPIC
            ),
            Sensor(
                name = "Media Title",
                serializedName = "media_title",
                icon = "mdi:information",
                subTopic = MEDIA_TITLE_SUB_TOPIC
            ),
            Sensor(
                name = "Media Duration",
                serializedName = "media_duration",
                icon = "mdi:clock",
                subTopic = MEDIA_DURATION_SUB_TOPIC,
                deviceClass = "duration",
                unitOfMeasurement = "ms"
            )
        )
        private val HASS_BUTTONS = listOf(
            Button(name = "Play", serializedName = "play", icon = "mdi:play", payload = "play"),
            Button(name = "Pause", serializedName = "pause", icon = "mdi:pause", payload = "pause"),
            Button(name = "Play/Pause", serializedName = "play_pause", icon = "mdi:play-pause", payload = "play_pause"),
            Button(name = "Stop", serializedName = "stop", icon = "mdi:stop", payload = "stop"),
            Button(name = "Next", serializedName = "next", icon = "mdi:skip-next", payload = "next"),
            Button(name = "Previous", serializedName = "previous", icon = "mdi:skip-previous", payload = "previous")
        )
    }
}
