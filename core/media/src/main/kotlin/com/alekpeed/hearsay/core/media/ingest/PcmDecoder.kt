package com.alekpeed.hearsay.core.media.ingest

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
        targetSampleRate: Int = DefaultTargetSampleRate,
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

            // The cap is on what is kept, not on what is read: mono at the analysis rate.
            val maxOutputSamples = if (maxDurationMs > 0) {
                (maxDurationMs / 1000.0 * targetSampleRate).toLong()
            } else {
                Long.MAX_VALUE
            }
            val expectedOutputSamples = if (totalDurationUs > 0) {
                (totalDurationUs / 1_000_000.0 * targetSampleRate).toInt()
            } else {
                0
            }.coerceAtMost(maxOutputSamples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

            val sink = DecodeSink(targetSampleRate, maxOutputSamples)
            sink.configure(
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                expectedOutputSamples,
            )

            drain(codec, extractor, sink, totalDurationUs, onProgress, expectedOutputSamples)

            onProgress(1f)
            Result.success(sink.toDecodedAudio())
        } catch (cancellation: CancellationException) {
            throw cancellation
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
        sink: DecodeSink,
        totalDurationUs: Long,
        onProgress: (Float) -> Unit,
        expectedOutputSamples: Int,
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
                    // The codec speaking for itself, which outranks the container's declaration
                    // even if audio has already been produced under it.
                    sink.configure(
                        newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        expectedOutputSamples,
                        authoritative = true,
                    )
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && bufferInfo.size > 0) sink.append(buffer, bufferInfo)
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (totalDurationUs > 0) {
                        onProgress((bufferInfo.presentationTimeUs.toFloat() / totalDurationUs).coerceIn(0f, 1f))
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    if (sink.isFull) sawOutputEnd = true
                }
            }
        }
    }

    companion object {
        private const val TimeoutUs = 10_000L

        /**
         * Analysis runs on mono at this rate. Harmony lives well below 11 kHz, so nothing useful is
         * discarded, and decoding straight to it means the full-rate stereo signal — by far the
         * largest thing in the pipeline — is never held in memory at all.
         */
        const val DefaultTargetSampleRate = 22_050

        /**
         * Analysis is capped at fifteen minutes of audio. Beyond that the memory cost climbs past
         * what a tablet should be asked for, and a longer recording is better handled in sections.
         */
        const val DefaultMaxDurationMs = 15 * 60 * 1000L
    }
}

class DecodeException(val failure: DecodeFailure) : Exception(failure.toString())

/** Growable float buffer; avoids the boxing an ArrayList<Float> would cost over millions of samples. */
internal class FloatArrayBuilder(initialCapacity: Int = 1 shl 20) {
    private var array = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }

    fun toFloatArray(): FloatArray = array.copyOf(size)
}

/**
 * Averages channels to mono and resamples, as the decoder produces samples.
 *
 * Doing it here rather than after the fact is the whole point. A seven-minute stereo track at
 * 44.1 kHz is 150 MB of float samples; the same audio as mono at the analysis rate is under 40 MB,
 * and the intermediate full-rate copies — the interleaved one, the downmixed one, the resampled one
 * — never exist at all. Holding them is what made a real tablet run out of memory.
 *
 * Resampling is linear interpolation with a one-sample carry across buffer boundaries, which is
 * what makes it streamable. That is the same quality as resampling afterwards: the target rate is
 * below the source and the spectral analysis downstream low-passes anyway.
 */
internal class MonoResampler(
    private val channels: Int,
    sourceRate: Int,
    targetRate: Int,
    private val output: FloatArrayBuilder,
) {
    private val ratio = if (targetRate > 0) sourceRate.toDouble() / targetRate else 1.0

    /** Where the next output sample sits, in source-sample coordinates. */
    private var nextOutputPosition = 0.0
    private var sourceIndex = 0L
    private var previous = 0f
    private var started = false

    /** One frame's worth of interleaved channel values, already converted to float. */
    fun accept(frame: FloatArray, count: Int) {
        if (count <= 0) return
        var sum = 0f
        for (channel in 0 until count) sum += frame[channel]
        push(sum / count)
    }

    private fun push(sample: Float) {
        if (!started) {
            started = true
            previous = sample
            // Position zero is the first sample itself; emit it and move on.
            if (nextOutputPosition <= 0.0) {
                output.add(sample)
                nextOutputPosition += ratio
            }
            sourceIndex = 1
            return
        }
        while (nextOutputPosition <= sourceIndex) {
            val base = kotlin.math.floor(nextOutputPosition)
            val fraction = (nextOutputPosition - base).toFloat()
            val interpolated = if (base >= sourceIndex) sample else previous + (sample - previous) * fraction
            output.add(interpolated)
            nextOutputPosition += ratio
        }
        previous = sample
        sourceIndex++
    }
}

/**
 * Collects decoded PCM as mono at the analysis sample rate.
 *
 * The codec may only announce its real channel count and rate once output starts, so the resampler
 * is built on the first configuration seen rather than up front.
 */
internal class DecodeSink(private val targetSampleRate: Int, private val maxOutputSamples: Long) {
    private var output = FloatArrayBuilder()
    private var resampler: MonoResampler? = null
    private var channels = 1
    private var sourceRate = 0
    private var frame = FloatArray(1)
    private var carry = FloatArray(0)
    private var carried = 0

    /** True once the codec's own output format has been applied, rather than the container's guess. */
    private var authoritative = false

    val size: Int get() = output.size
    val isFull: Boolean get() = output.size >= maxOutputSamples

    /**
     * (Re)configures the decode target, preferring the most authoritative format seen so far.
     *
     * This is called twice in the ordinary case: once from the container's declared format before
     * decoding starts, as a fallback for the rare codec that never reports its own; and again from
     * the decoder's actual output format once decoding is under way, which is called out
     * specifically because it can disagree with the container. HE-AAC is the textbook case — the
     * container reports the AAC core rate, and the codec then produces audio at twice that once it
     * applies spectral band replication. Trusting the container's guess after the codec has spoken
     * silently doubled or halved playback speed for such a file, and with it every tempo estimate
     * and chord timestamp computed downstream: a real recording playing back at the wrong speed
     * looks to the analyzer exactly like a recording whose tempo and harmony are genuinely that far
     * off, because as far as the analyzer can tell, they are.
     *
     * Once real audio has been produced, a further *guess* is refused — disturbing the resampler
     * would misalign or drop audio already decoded, and a container's second opinion is not worth
     * that. The codec's own format is different in kind, and [authoritative] is what separates
     * them. When it arrives late and disagrees, the audio decoded so far was decoded under an
     * assumption now known to be wrong, so it is discarded and decoding restarts on the real
     * format. That costs the handful of buffers a device emits before announcing itself; keeping
     * them costs the whole file, because a wrong channel count packs two samples into one frame
     * and a wrong sample rate scales the resampler's ratio — each of which lands as exactly the
     * factor-of-two error that made a track report double its true tempo on one platform while
     * reporting it correctly on another.
     *
     * The previous guard could not draw this distinction: it kept whichever format arrived before
     * the first buffer, so a device that emits audio ahead of `INFO_OUTPUT_FORMAT_CHANGED` was
     * locked to the container's declared values for the entire track.
     */
    fun configure(
        channels: Int,
        sourceRate: Int,
        expectedOutputSamples: Int,
        authoritative: Boolean = false,
    ) {
        val requested = channels.coerceAtLeast(1)
        // A guess never displaces what the codec itself has already said.
        if (this.authoritative && !authoritative) return

        if (resampler != null && output.size > 0) {
            // Only the codec's own format may override a guess this late, and only the once.
            if (!authoritative || this.authoritative) return
            if (requested == this.channels && sourceRate == this.sourceRate) {
                this.authoritative = true
                return
            }
        }

        this.channels = requested
        this.sourceRate = sourceRate
        this.authoritative = authoritative
        frame = FloatArray(requested)
        carry = FloatArray(requested)
        carried = 0
        // Sized from the track's duration so the buffer never doubles, which would briefly hold
        // both the old and the new array.
        output = FloatArrayBuilder(expectedOutputSamples.coerceAtLeast(1 shl 16))
        resampler = MonoResampler(requested, sourceRate, targetSampleRate, output)
    }

    fun append(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val resampler = resampler ?: return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        // A codec buffer can in principle end mid-frame. Carrying the remainder keeps the channel
        // alignment; dropping it would silently rotate left and right for the rest of the file.
        while (shorts.hasRemaining() && !isFull) {
            carry[carried++] = shorts.get() / 32_768f
            if (carried == channels) {
                carry.copyInto(frame)
                resampler.accept(frame, channels)
                carried = 0
            }
        }
    }

    fun toDecodedAudio(): DecodedAudio =
        DecodedAudio(output.toFloatArray(), channels = 1, sampleRate = targetSampleRate)
}
