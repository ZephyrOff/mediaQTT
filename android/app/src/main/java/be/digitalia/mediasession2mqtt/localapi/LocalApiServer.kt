package be.digitalia.mediasession2mqtt.localapi

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * Minimal WebSocket server exposing the playback state to authenticated clients.
 *
 * Clients must connect to [PATH] on all interfaces and send the header "Authorization: Bearer <token>":
 * the handshake is refused otherwise. The token is never logged.
 *
 * [onCommand] is called from a network thread. It must answer through [sendResult].
 */
class LocalApiServer(
    port: Int,
    token: String,
    private val onCommand: (client: WebSocket, request: CommandRequest) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    class CommandRequest(val id: Any?, val command: String, val positionInMillis: Long?)

    private val expectedAuthorization = "Bearer $token".toByteArray(Charsets.UTF_8)

    @Volatile
    private var latestState: String? = null

    init {
        isReuseAddr = true
        // Detect dead connections (e.g. a client that went to sleep) with periodic pings
        connectionLostTimeout = CONNECTION_LOST_TIMEOUT_SECONDS
    }

    /** Remember the state for the clients which connect later and send it to the connected ones. */
    fun publishState(state: LocalApiState) {
        val message = state.toJson()
        latestState = message
        for (client in connections) {
            sendQuietly(client, message)
        }
    }

    fun sendResult(client: WebSocket, id: Any?, error: String?) {
        val json = JSONObject()
        json.put("id", id ?: JSONObject.NULL)
        json.put("type", "result")
        json.put("ok", error == null)
        if (error != null) {
            json.put("error", error)
        }
        sendQuietly(client, json.toString())
    }

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: Draft,
        request: ClientHandshake
    ): ServerHandshakeBuilder {
        val path = request.resourceDescriptor.substringBefore('?')
        // MessageDigest.isEqual runs in constant time
        val isAuthorized = path == PATH && MessageDigest.isEqual(
            expectedAuthorization,
            request.getFieldValue("Authorization").toByteArray(Charsets.UTF_8)
        )
        if (!isAuthorized) {
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized")
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        latestState?.let { sendQuietly(conn, it) }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = try {
            JSONObject(message)
        } catch (_: JSONException) {
            sendResult(conn, null, "invalid_message")
            return
        }
        val id = json.opt("id").takeIf { it is Number || it is String }
        if (json.optString("type") != "command") {
            sendResult(conn, id, "invalid_message")
            return
        }
        val command = json.optString("command")
        val position = if (json.has("position_ms")) json.optLong("position_ms", -1L) else null
        onCommand(conn, CommandRequest(id, command, position))
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "Local API error: ${ex.javaClass.simpleName}")
    }

    override fun onStart() {
        Log.i(TAG, "Local API listening on port $port")
    }

    private fun sendQuietly(client: WebSocket, message: String) {
        try {
            client.send(message)
        } catch (_: Exception) {
            // The client is gone: its close callback will clean up
        }
    }

    companion object {
        const val PATH = "/ws"
        private const val TAG = "LocalApiServer"
        private const val CONNECTION_LOST_TIMEOUT_SECONDS = 30
    }
}
