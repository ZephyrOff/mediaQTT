package be.digitalia.mediasession2mqtt.homeassistant

/**
 * A Home Assistant MQTT button: pressing it publishes [payload] on the command topic.
 */
class Button(
    val name: String,
    private val serializedName: String,
    val icon: String,
    val payload: String
) {
    val type: String
        get() = "button"

    fun getUniqueId(deviceId: Int): String {
        return "mediasession_${deviceId}_$serializedName"
    }
}
