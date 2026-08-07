package com.alekpeed.hearsay.core.media.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The resampler runs while the file decodes, so it never sees the whole signal.
 *
 * That is the point — holding the full-rate stereo signal is what ran a tablet out of memory — but
 * it means a mistake here shows up as audio that is subtly wrong rather than as a failure: a rate
 * that drifts, channels that swap, a boundary that clicks. None of that would fail a build.
 */
class MonoResamplerTest {

    private fun resample(
        source: FloatArray,
        channels: Int,
        sourceRate: Int,
        targetRate: Int,
        chunkFrames: Int = 64,
    ): FloatArray {
        val output = FloatArrayBuilder(1024)
        val resampler = MonoResampler(channels, sourceRate, targetRate, output)
        val frame = FloatArray(channels)
        var index = 0
        val frameCount = source.size / channels
        while (index < frameCount) {
            val end = minOf(index + chunkFrames, frameCount)
            for (f in index until end) {
                for (c in 0 until channels) frame[c] = source[f * channels + c]
                resampler.accept(frame, channels)
            }
            index = end
        }
        return output.toFloatArray()
    }

    @Test
    fun `halving the rate halves the sample count`() {
        val source = FloatArray(4_410) { it.toFloat() }

        val out = resample(source, channels = 1, sourceRate = 44_100, targetRate = 22_050)

        // One sample either side is tolerated: the last output may have no right neighbor to read.
        assertTrue("expected about 2205, got ${out.size}", abs(out.size - 2_205) <= 1)
    }

    @Test
    fun `an unrelated rate still lands on the right duration`() {
        // 48 kHz is the other rate a phone recording actually arrives at, and 48000/22050 is not a
        // whole number — the case a naive "take every Nth sample" decimator gets wrong.
        val seconds = 3
        val source = FloatArray(48_000 * seconds) { it.toFloat() }

        val out = resample(source, channels = 1, sourceRate = 48_000, targetRate = 22_050)

        assertTrue("expected about ${22_050 * seconds}, got ${out.size}", abs(out.size - 22_050 * seconds) <= 2)
    }

    @Test
    fun `stereo is averaged, not interleaved`() {
        // Left constant 1, right constant -1: any output but silence means the channels were read
        // as consecutive samples rather than as one frame.
        val frames = 1_000
        val source = FloatArray(frames * 2) { if (it % 2 == 0) 1f else -1f }

        val out = resample(source, channels = 2, sourceRate = 22_050, targetRate = 22_050)

        assertTrue(out.isNotEmpty())
        for (value in out) assertEquals(0f, value, 1e-6f)
    }

    @Test
    fun `the result does not depend on how the decoder chunked its output`() {
        // Codec buffer sizes are not ours to choose, and a boundary that loses or duplicates a
        // sample would shift everything after it — audible as drift, invisible to any other test.
        val source = FloatArray(9_000) { sin(it * 0.01f) }

        val inOneGo = resample(source, 1, 44_100, 22_050, chunkFrames = 9_000)
        val inSmallChunks = resample(source, 1, 44_100, 22_050, chunkFrames = 7)
        val inOddChunks = resample(source, 1, 44_100, 22_050, chunkFrames = 337)

        assertEquals(inOneGo.size, inSmallChunks.size)
        assertEquals(inOneGo.size, inOddChunks.size)
        for (i in inOneGo.indices) {
            assertEquals("sample $i", inOneGo[i], inSmallChunks[i], 1e-6f)
            assertEquals("sample $i", inOneGo[i], inOddChunks[i], 1e-6f)
        }
    }

    @Test
    fun `a matching rate passes the signal through unchanged`() {
        val source = FloatArray(500) { sin(it * 0.05f) }

        val out = resample(source, channels = 1, sourceRate = 22_050, targetRate = 22_050)

        assertEquals(source.size, out.size)
        for (i in source.indices) assertEquals("sample $i", source[i], out[i], 1e-6f)
    }

    @Test
    fun `a ramp stays a ramp rather than picking up a step`() {
        // Linear input is the case where interpolation has an exactly known answer, so any error in
        // the fractional position shows up immediately.
        val source = FloatArray(2_000) { it.toFloat() }

        val out = resample(source, channels = 1, sourceRate = 44_100, targetRate = 22_050)

        for (i in out.indices) assertEquals("sample $i", i * 2f, out[i], 1e-3f)
    }
}
