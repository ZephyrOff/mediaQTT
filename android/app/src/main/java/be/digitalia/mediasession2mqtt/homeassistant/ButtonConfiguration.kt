package be.digitalia.mediasession2mqtt.homeassistant

import android.util.JsonWriter
import java.io.StringWriter

fun createButtonDiscoveryConfiguration(deviceId: Int, button: Button, commandTopic: String): String {
    val writer = StringWriter()
    JsonWriter(writer).use {
        it.beginObject()

        it.name("name")
        it.value(button.name)
        it.name("unique_id")
        it.value(button.getUniqueId(deviceId))
        it.name("icon")
        it.value(button.icon)
        it.name("command_topic")
        it.value(commandTopic)
        it.name("payload_press")
        it.value(button.payload)
        it.name("device")
        it.writeDeviceInfo(deviceId)

        it.endObject()
    }
    return writer.toString()
}
