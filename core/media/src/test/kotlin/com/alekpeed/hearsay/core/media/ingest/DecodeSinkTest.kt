package com.alekpeed.hearsay.core.media.ingest

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whether the decoder trusts the codec's real output format over the container's declared one.
 *
 * `decode()` calls [DecodeSink.configure] twice: once from the container's metadata, before any
 * decoding has happened, and again from the codec's actual output format once decoding starts. A
 * guard that let only the first call take effect silently kept whichever format arrived first —
 * which for HE-AAC (SBR) content is the container's core rate, not the doubled rate the codec
 * actually produces. That is a decode-speed bug, not a decode-failure one: the file still decodes,
 * just at the wrong rate, so every timestamp computed from it — tempo, beats, chord regions — is
 * wrong by the same ratio, with nothing in the pipeline positioned to notice.
 */
@RunWith(RobolectricTestRunner::class)
class DecodeSinkTest {

    private fun sample(value: Short): ByteBuffer =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).apply { rewind() }

    private fun bufferInfoFor(size: Int) = MediaCodec.BufferInfo().apply { set(0, size, 0, 0) }

    @Test
    fun `the codec's real format wins over the container's declared one`() {
        val sink = DecodeSink(targetSampleRate = 22_050, maxOutputSamples = Long.MAX_VALUE)

        // The container's guess, applied before any decoding — as decode() does up front.
        sink.configure(channels = 2, sourceRate = 22_050, expectedOutputSamples = 0)
        // The codec's real output format, arriving as decode() forwards INFO_OUTPUT_FORMAT_CHANGED.
        // Real HE-AAC content is exactly this: container declares the AAC core rate, decoder then
        // doubles it via spectral band replication.
        sink.configure(channels = 2, sourceRate = 44_100, expectedOutputSamples = 0)

        // A one-second burst of silence, decoded as though the source is genuinely 44.1 kHz. If the
        // stale 22.05 kHz configuration had survived, this would resample to double the true
        // duration — exactly the "everything plays back too fast" failure this exists to catch.
        val oneSecondOfStereoSilence = ByteBuffer.allocate(44_100 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        sink.append(oneSecondOfStereoSilence, bufferInfoFor(oneSecondOfStereoSilence.capacity()))

        val decoded = sink.toDecodedAudio()
        val expectedSamples = 22_050 // one second at the 22.05 kHz target rate
        assertEquals(
            "Expected about one second of audio at the target rate; a stale source rate would " +
                "produce roughly double this",
            expectedSamples.toDouble(),
            decoded.samples.size.toDouble(),
            expectedSamples * 0.02,
        )
    }

    @Test
    fun `a reconfigure is ignored once real audio has already been produced`() {
        val sink = DecodeSink(targetSampleRate = 22_050, maxOutputSamples = Long.MAX_VALUE)
        sink.configure(channels = 1, sourceRate = 22_050, expectedOutputSamples = 0)

        val buffer = sample(1000)
        sink.append(buffer, bufferInfoFor(2))
        val sizeBeforeReconfigure = sink.size

        // A late, disagreeing reconfigure must not disturb audio already decoded — resetting the
        // resampler mid-stream would misalign or drop it, which is worse than keeping a stale rate
        // for the rare codec that reports its format unusually.
        sink.configure(channels = 2, sourceRate = 44_100, expectedOutputSamples = 0)

        assertEquals(sizeBeforeReconfigure, sink.size)
    }
}
