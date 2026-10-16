package be.digitalia.mediasession2mqtt.mediasession

import android.media.session.MediaController
import android.media.session.PlaybackState

/**
 * A playback command received over MQTT and applied to the current MediaSession.
 */
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand

    /** Pauses if the media is currently playing, plays otherwise. */
    data object PlayPause : MediaCommand
    data object Stop : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data class SeekTo(val positionInMillis: Long) : MediaCommand
}

/**
 * Parse the payload of the "command" topic.
 * Returns null for unknown payloads, which must be ignored.
 */
fun parseMediaCommand(payload: String): MediaCommand? = when (payload.trim().lowercase()) {
    "play" -> MediaCommand.Play
    "pause" -> MediaCommand.Pause
    "play_pause" -> MediaCommand.PlayPause
    "stop" -> MediaCommand.Stop
    "next" -> MediaCommand.Next
    "previous" -> MediaCommand.Previous
    else -> null
}

/**
 * Parse the payload of the "seek" topic: an absolute position in milliseconds.
 * Returns null for anything that is not a non-negative integer, which must be ignored.
 */
fun parseSeekCommand(payload: String): MediaCommand.SeekTo? {
    val positionInMillis = payload.trim().toLongOrNull() ?: return null
    return if (positionInMillis >= 0L) MediaCommand.SeekTo(positionInMillis) else null
}

/**
 * Apply the command to this session. Whether the application actually honours it depends on the actions
 * it declares in its PlaybackState: unsupported commands are silently ignored by the application.
 */
fun MediaController.execute(command: MediaCommand) {
    val controls = transportControls
    when (command) {
        MediaCommand.Play -> controls.play()
        MediaCommand.Pause -> controls.pause()
        MediaCommand.PlayPause -> {
            // Buffering is deliberately not considered as playing: some applications pre-buffer
            // before the user requests playback.
            if (playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
        }

        MediaCommand.Stop -> controls.stop()
        MediaCommand.Next -> controls.skipToNext()
        MediaCommand.Previous -> controls.skipToPrevious()
        is MediaCommand.SeekTo -> controls.seekTo(command.positionInMillis)
    }
}
