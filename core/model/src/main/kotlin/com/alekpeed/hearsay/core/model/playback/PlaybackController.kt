package com.alekpeed.hearsay.core.model.playback

import com.alekpeed.hearsay.core.model.timeline.LoopRange
import kotlinx.coroutines.flow.StateFlow

/** What the UI is allowed to know about the player. */
data class PlaybackState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val pitchPreserved: Boolean = true,
    val loop: LoopRange? = null,
    val mediaId: String? = null,
    val error: PlaybackError? = null,
)

sealed interface PlaybackError {
    data class SourceUnavailable(val mediaId: String) : PlaybackError
    data class DecoderUnsupported(val mimeType: String?) : PlaybackError
    data class Unknown(val message: String) : PlaybackError
}

/** What to play, resolved to something the media layer can open. */
data class PlaybackRequest(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
)

/**
 * The one clock in the app. Every visual timeline — table row highlighting, waveform cursor,
 * count-in — derives from this position rather than running a timer of its own.
 *
 * Implemented over Media3 in `:core:media`; features depend only on this interface so playback can
 * be faked in tests without a player.
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    fun prepare(request: PlaybackRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setLoop(loop: LoopRange?)
    fun release()
}

/**
 * A click track aligned to the beat grid.
 *
 * Deliberately only an interface for now. The spec flags metronome latency as unvalidated, and
 * committing to an implementation before measuring it on real hardware would bake in a guess.
 */
interface Metronome {
    val isEnabled: Boolean
    fun setEnabled(enabled: Boolean)
    fun setCountInBeats(beats: Int)
}
