package be.digitalia.mediasession2mqtt.localapi

import android.media.MediaMetadata
import android.media.session.PlaybackState
import be.digitalia.mediasession2mqtt.mediasession.MediaCommand
import be.digitalia.mediasession2mqtt.mqttmediaplayer.MQTTPlaybackState
import be.digitalia.mediasession2mqtt.mqttmediaplayer.toMediaTitle
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Snapshot of the current playback, as sent to the clients of the local API.
 *
 * [positionUpdatedAtEpochMillis] is the wall clock time (UTC) at which [positionInMillis] was valid,
 * so that the client can extrapolate the position while playing.
 */
data class LocalApiState(
    val state: String,
    val appId: String?,
    val title: String?,
    val durationInMillis: Long?,
    val positionInMillis: Long?,
    val positionUpdatedAtEpochMillis: Long?,
    val supportedActions: List<String>
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", "state")
        json.put("state", state)
        json.put("app_id", appId ?: JSONObject.NULL)
        json.put("title", title ?: JSONObject.NULL)
        json.put("duration_ms", durationInMillis ?: JSONObject.NULL)
        json.put("position_ms", positionInMillis ?: JSONObject.NULL)
        json.put(
            "position_updated_at",
            positionUpdatedAtEpochMillis?.let { formatUtc(it) } ?: JSONObject.NULL
        )
        json.put("supported_actions", JSONArray(supportedActions))
        return json.toString()
    }

    /**
     * Whether a client would not notice the difference between this snapshot and [other].
     * Playing positions are compared after compensating for the elapsed time, so that a regular position
     * update of an application which didn't seek does not generate a new message.
     */
    fun isEquivalentTo(other: LocalApiState): Boolean {
        if (state != other.state || appId != other.appId || title != other.title ||
            durationInMillis != other.durationInMillis || supportedActions != other.supportedActions
        ) {
            return false
        }
        if (positionInMillis == null || other.positionInMillis == null) {
            return positionInMillis == other.positionInMillis
        }
        if (state != STATE_PLAYING || positionUpdatedAtEpochMillis == null || other.positionUpdatedAtEpochMillis == null) {
            return positionInMillis == other.positionInMillis
        }
        val drift = (other.positionInMillis - positionInMillis) -
                (other.positionUpdatedAtEpochMillis - positionUpdatedAtEpochMillis)
        return abs(drift) < POSITION_DRIFT_THRESHOLD_MILLIS
    }

    companion object {
        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_IDLE = "idle"

        private const val POSITION_DRIFT_THRESHOLD_MILLIS = 1000L

        val NO_SESSION = LocalApiState(STATE_IDLE, null, null, null, null, null, emptyList())

        private fun formatUtc(epochMillis: Long): String {
            // SimpleDateFormat is not thread safe: create one per call
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(epochMillis))
        }
    }
}

/**
 * Build a snapshot from a stable playback state (see toMQTTPlaybackStateOrNull).
 *
 * @param elapsedRealtimeMillis current SystemClock.elapsedRealtime(), the time base of lastPositionUpdateTime
 * @param nowEpochMillis current wall clock time
 */
fun buildLocalApiState(
    packageName: String,
    playbackState: PlaybackState?,
    mqttState: MQTTPlaybackState,
    metadata: MediaMetadata?,
    elapsedRealtimeMillis: Long,
    nowEpochMillis: Long
): LocalApiState {
    val position = mqttState.positionInMillis.takeIf { it >= 0L }
    val lastUpdate = playbackState?.lastPositionUpdateTime ?: 0L
    val positionUpdatedAt = when {
        position == null -> null
        lastUpdate > 0L -> nowEpochMillis - (elapsedRealtimeMillis - lastUpdate)
        else -> nowEpochMillis
    }
    return LocalApiState(
        state = mqttState.name,
        appId = packageName,
        title = metadata.toLocalApiTitle(),
        durationInMillis = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L },
        positionInMillis = position,
        positionUpdatedAtEpochMillis = positionUpdatedAt,
        supportedActions = playbackState.supportedActionNames()
    )
}

/**
 * Same as toMediaTitle(), with a fallback on the media description, which is what "dumpsys media_session" prints.
 */
private fun MediaMetadata?.toLocalApiTitle(): String? {
    if (this == null) {
        return null
    }
    return toMediaTitle().ifEmpty { description.title?.toString().orEmpty() }.ifEmpty { null }
}

private const val PLAY_PAUSE_ACTIONS =
    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE

/**
 * The declared actions reflect the current state (e.g. ACTION_PLAY is absent while playing),
 * so play and pause are both considered supported as soon as one of PLAY, PAUSE or PLAY_PAUSE is declared.
 */
fun PlaybackState?.supportedActionNames(): List<String> {
    val actions = this?.actions ?: return emptyList()
    return buildList {
        if (actions and PLAY_PAUSE_ACTIONS != 0L) {
            add("play")
            add("pause")
        }
        if (actions and PlaybackState.ACTION_STOP != 0L) add("stop")
        if (actions and PlaybackState.ACTION_SEEK_TO != 0L) add("seek")
        if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) add("next")
        if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) add("previous")
    }
}

/**
 * Whether the application declares support for [command] in its current PlaybackState.
 */
fun PlaybackState?.supports(command: MediaCommand): Boolean {
    val actions = this?.actions ?: return false
    return when (command) {
        MediaCommand.Play, MediaCommand.Pause, MediaCommand.PlayPause -> actions and PLAY_PAUSE_ACTIONS != 0L
        MediaCommand.Stop -> actions and PlaybackState.ACTION_STOP != 0L
        MediaCommand.Next -> actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        MediaCommand.Previous -> actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        is MediaCommand.SeekTo -> actions and PlaybackState.ACTION_SEEK_TO != 0L
    }
}
