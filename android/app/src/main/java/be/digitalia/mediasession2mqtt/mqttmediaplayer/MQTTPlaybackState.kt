package be.digitalia.mediasession2mqtt.mqttmediaplayer

sealed interface MQTTPlaybackState {
    val name: String
    val positionInMillis: Long

    data object Idle : MQTTPlaybackState {
        override val name: String
            get() = "idle"
        override val positionInMillis: Long
            get() = -1
    }

    data class Playing(override val positionInMillis: Long) : MQTTPlaybackState {
        override val name: String
            get() = "playing"
    }

    data class Paused(override val positionInMillis: Long) : MQTTPlaybackState {
        override val name: String
            get() = "paused"
    }
}
