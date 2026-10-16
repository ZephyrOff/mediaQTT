package be.digitalia.mediasession2mqtt.settings

data class LocalApiSettings(
    val port: Int,
    val token: String
) {
    // Never expose the token in logs
    override fun toString(): String = "LocalApiSettings(port=$port)"
}
