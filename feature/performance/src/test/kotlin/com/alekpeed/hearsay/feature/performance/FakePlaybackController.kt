package com.alekpeed.hearsay.feature.performance

import com.alekpeed.hearsay.core.model.playback.PlaybackController
import com.alekpeed.hearsay.core.model.playback.PlaybackRequest
import com.alekpeed.hearsay.core.model.playback.PlaybackState
import com.alekpeed.hearsay.core.model.timeline.LoopRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A player with no audio in it.
 *
 * The ViewModel's job is to turn a position into a table row, so the tests move the position by
 * hand and assert on what the screen would show.
 */
class FakePlaybackController : PlaybackController {

    private val _state = MutableStateFlow(PlaybackState(isConnected = true))
    override val state: StateFlow<PlaybackState> = _state

    val preparedRequests = mutableListOf<PlaybackRequest>()
    val seeks = mutableListOf<Long>()

    override fun prepare(request: PlaybackRequest) {
        preparedRequests += request
        _state.value = _state.value.copy(mediaId = request.mediaId, durationMs = request.durationMs)
    }

    override fun play() {
        _state.value = _state.value.copy(isPlaying = true)
    }

    override fun pause() {
        _state.value = _state.value.copy(isPlaying = false)
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    override fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
    }

    override fun setLoop(loop: LoopRange?) {
        _state.value = _state.value.copy(loop = loop)
    }

    override fun release() {
        _state.value = PlaybackState()
    }

    /** Moves the clock without going through a seek, the way real playback advances it. */
    fun advanceTo(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs)
    }
}
