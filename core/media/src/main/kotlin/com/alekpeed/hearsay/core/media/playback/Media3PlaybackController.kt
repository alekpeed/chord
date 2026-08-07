package com.alekpeed.hearsay.core.media.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.alekpeed.hearsay.core.model.playback.PlaybackController
import com.alekpeed.hearsay.core.model.playback.PlaybackError
import com.alekpeed.hearsay.core.model.playback.PlaybackRequest
import com.alekpeed.hearsay.core.model.playback.PlaybackState
import com.alekpeed.hearsay.core.model.timeline.LoopRange
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single playback clock, implemented over a Media3 [MediaController].
 *
 * Position is published by polling the controller rather than by running an independent timer, so
 * the chord table can never drift away from what is actually sounding. Nothing outside this class
 * touches ExoPlayer.
 */
@Singleton
class Media3PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var positionJob: Job? = null
    private var loop: LoopRange? = null
    private var pendingRequest: PlaybackRequest? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = error.toDomain())
        }
    }

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()?.also { it.addListener(listener) }
                _state.value = _state.value.copy(isConnected = controller != null)
                pendingRequest?.let { prepare(it) }
                pendingRequest = null
                publish()
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun prepare(request: PlaybackRequest) {
        val player = controller ?: run {
            // The session may not be bound yet on a cold start; replay the request once it is.
            pendingRequest = request
            return
        }
        if (_state.value.mediaId == request.mediaId) return

        val item = MediaItem.Builder()
            .setMediaId(request.mediaId)
            .setUri(Uri.parse(request.uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title)
                    .setArtist(request.artist)
                    .build(),
            )
            .build()

        player.setMediaItem(item)
        player.prepare()
        _state.value = _state.value.copy(
            mediaId = request.mediaId,
            durationMs = request.durationMs,
            error = null,
        )
        startPositionUpdates()
    }

    override fun play() {
        controller?.play()
    }

    override fun pause() {
        controller?.pause()
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    override fun setSpeed(speed: Float) {
        // Media3's default audio processor time-stretches without shifting pitch, which is what a
        // player slowing a passage down expects.
        controller?.playbackParameters = PlaybackParameters(speed, 1f)
        _state.value = _state.value.copy(speed = speed)
    }

    override fun setLoop(loop: LoopRange?) {
        this.loop = loop
        _state.value = _state.value.copy(loop = loop)
        if (loop != null) startPositionUpdates()
    }

    override fun release() {
        positionJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = PlaybackState()
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                publish()
                enforceLoop()
                delay(PositionPollMs)
            }
        }
    }

    /** Loops are enforced against the player clock; a range that has been passed seeks back. */
    private fun enforceLoop() {
        val player = controller ?: return
        val range = loop ?: return
        if (!player.isPlaying) return
        if (player.currentPosition >= range.endMs) {
            player.seekTo(range.startMs)
        }
    }

    private fun publish() {
        val player = controller
        if (player == null) {
            _state.value = _state.value.copy(isConnected = false, isPlaying = false)
            return
        }
        val duration = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
        _state.value = _state.value.copy(
            isConnected = true,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            speed = player.playbackParameters.speed,
            loop = loop,
        )
    }

    private fun PlaybackException.toDomain(): PlaybackError = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> PlaybackError.SourceUnavailable(_state.value.mediaId.orEmpty())

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        -> PlaybackError.DecoderUnsupported(null)

        else -> PlaybackError.Unknown(errorCodeName)
    }

    private companion object {
        /** Fast enough that a chord row change is imperceptible, slow enough to be free. */
        const val PositionPollMs = 50L
    }
}
