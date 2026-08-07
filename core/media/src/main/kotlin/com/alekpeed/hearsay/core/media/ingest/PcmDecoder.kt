package com.alekpeed.hearsay.core.media.ingest

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Decoded audio handed to the analyzer. */
data class DecodedAudio(
    val samples: FloatArray,
    val channels: Int,
    val sampleRate: Int,
) {
    val durationMs: Long
        get() = if (sampleRate > 0 && channels > 0) {
            (samples.size.toLong() / channels * 1000L) / sampleRate
        } else {
            0L
        }

    override fun equals(other: Any?): Boolean =
        other is DecodedAudio && other.channels == channels && other.sampleRate == sampleRate &&
            other.samples.contentEquals(samples)

    override fun hashCode(): Int = samples.contentHashCode() * 31 + channels * 31 + sampleRate
}

sealed interface DecodeFailure {
    data object NoAudioTrack : DecodeFailure
    data class UnsupportedFormat(val mimeType: String?) : DecodeFailure
    data class Unreadable(val message: String) : DecodeFailure
}

/**
 * Decodes any container the device can open into linear PCM.
 *
 * Uses the platform decoders rather than bundling our own, which is what makes the supported-format
 * list match whatever the tablet can actually play. Decoding is cancellable at frame granularity:
 * a user backing out of an analysis should not have to wait for a ten-minute file to finish.
 */
@Singleton
class PcmDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(HearsayDispatcher.Decode) private val decodeDispatcher: CoroutineDispatcher,
) {

    suspend fun decode(
        uri: Uri,
        maxDurationMs: Long = DefaultMaxDurationMs,
        onProgress: (Float) -> Unit = {},
    ): Result<DecodedAudio> = withContext(decodeDispatcher) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext Result.failure(DecodeException(DecodeFailure.NoAudioTrack))

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return@withContext Result.failure(DecodeException(DecodeFailure.UnsupportedFormat(null)))

            extractor.selectTrack(trackIndex)
            codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
                ?: return@withContext Result.failure(DecodeException(DecodeFailure.UnsupportedFormat(mime)))

            codec.configure(format, null, null, 0)
            codec.start()

            val totalDurationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            val output = FloatArrayBuilder()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val maxSamples = if (maxDurationMs > 0) {
                (maxDurationMs / 1000.0 * sampleRate * channels).toLong()
            } else {
                Long.MAX_VALUE
            }

            drain(codec, extractor, output, maxSamples, totalDurationUs, onProgress) { newChannels, newRate ->
                channels = newChannels
                sampleRate = newRate
            }

            onProgress(1f)
            Result.success(DecodedAudio(output.toFloatArray(), channels, sampleRate))
        } catch (error: Exception) {
            Result.failure(DecodeException(DecodeFailure.Unreadable(error.message ?: "Decoding failed")))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Pumps buffers through the codec until the stream ends or the cap is reached.
     *
     * Split out from [decode] because the loop is the only genuinely intricate part: everything
     * around it is setup and teardown, and mixing the two made both harder to follow.
     */
    @Suppress("LongParameterList")
    private suspend fun drain(
        codec: MediaCodec,
        extractor: MediaExtractor,
        output: FloatArrayBuilder,
        maxSamples: Long,
        totalDurationUs: Long,
        onProgress: (Float) -> Unit,
        onFormatChanged: (channels: Int, sampleRate: Int) -> Unit,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false

        while (!sawOutputEnd) {
            coroutineContext.ensureActive()

            if (!sawInputEnd) {
                val inputIndex = codec.dequeueInputBuffer(TimeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val read = extractor.readSampleData(inputBuffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TimeoutUs)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    onFormatChanged(
                        newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    )
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && bufferInfo.size > 0) appendPcm(buffer, bufferInfo, output)
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (totalDurationUs > 0) {
                        onProgress((bufferInfo.presentationTimeUs.toFloat() / totalDurationUs).coerceIn(0f, 1f))
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    if (output.size >= maxSamples) sawOutputEnd = true
                }
            }
        }
    }

    /** 16-bit PCM is what the platform decoders emit; anything else is converted by the codec. */
    private fun appendPcm(buffer: ByteBuffer, info: MediaCodec.BufferInfo, output: FloatArrayBuilder) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        while (shorts.hasRemaining()) {
            output.add(shorts.get() / 32_768f)
        }
    }

    private companion object {
        const val TimeoutUs = 10_000L

        /**
         * Analysis is capped at fifteen minutes of audio. Beyond that the memory cost climbs past
         * what a tablet should be asked for, and a longer recording is better handled in sections.
         */
        const val DefaultMaxDurationMs = 15 * 60 * 1000L
    }
}

class DecodeException(val failure: DecodeFailure) : Exception(failure.toString())

/** Growable float buffer; avoids the boxing an ArrayList<Float> would cost over millions of samples. */
private class FloatArrayBuilder(initialCapacity: Int = 1 shl 20) {
    private var array = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }

    fun toFloatArray(): FloatArray = array.copyOf(size)
}
