package com.alekpeed.hearsay.core.audio.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Decoded audio, normalized to what the analysis expects.
 *
 * Analysis runs on mono at a reduced sample rate — harmony lives well below 11 kHz, and every
 * halving of the rate is a halving of every FFT that follows. The stereo image is kept separately
 * where it is useful, because the difference between the channels is what lets centered vocals be
 * pushed down before chord detection.
 */
class AudioBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    val durationMs: Long get() = (samples.size * 1000L) / sampleRate

    /**
     * A window of [length] samples starting at [startSample], zero-padded at either edge.
     *
     * A negative start is padded on the left rather than returning silence. Windows are centered on
     * the time they represent, so the first few genuinely begin before the recording does, and
     * treating those as empty would blind the analysis to the opening bar.
     */
    fun frameAt(startSample: Int, length: Int): FloatArray {
        val out = FloatArray(length)
        val from = max(0, startSample)
        val to = min(samples.size, startSample + length)
        if (to > from) {
            samples.copyInto(out, from - startSample, from, to)
        }
        return out
    }

    companion object {
        const val AnalysisSampleRate = 22_050

        /** Averages channels down to mono. */
        fun mono(interleaved: FloatArray, channels: Int, sampleRate: Int): AudioBuffer {
            if (channels <= 1) return AudioBuffer(interleaved, sampleRate)
            val frames = interleaved.size / channels
            val out = FloatArray(frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
                out[frame] = sum / channels
            }
            return AudioBuffer(out, sampleRate)
        }

        /**
         * Mid-side reduction of whatever sits in the center of the stereo image.
         *
         * Lead vocals are almost always panned center, so the channel difference is a cheap
         * approximation of "everything except the singer". It is not source separation and is not
         * presented as such — it is a preprocessing step that measurably helps chord detection on
         * vocal-heavy mixes, and it does nothing at all to a mono recording.
         */
        fun sideChannel(interleaved: FloatArray, channels: Int, sampleRate: Int): AudioBuffer? {
            if (channels != 2) return null
            val frames = interleaved.size / 2
            val out = FloatArray(frames)
            for (frame in 0 until frames) {
                out[frame] = (interleaved[frame * 2] - interleaved[frame * 2 + 1]) * 0.5f
            }
            return AudioBuffer(out, sampleRate)
        }
    }
}

/**
 * Linear-interpolating resampler.
 *
 * Adequate because the target rate is always below the source and the signal is low-passed by the
 * spectral analysis that follows; a windowed-sinc kernel would cost more than it recovers here.
 */
fun AudioBuffer.resampledTo(targetRate: Int): AudioBuffer {
    if (targetRate == sampleRate) return this
    val ratio = sampleRate.toDouble() / targetRate
    val outLength = (samples.size / ratio).toInt()
    val out = FloatArray(outLength)
    for (i in 0 until outLength) {
        val position = i * ratio
        val index = position.toInt()
        val fraction = (position - index).toFloat()
        val a = samples.getOrElse(index) { 0f }
        val b = samples.getOrElse(index + 1) { a }
        out[i] = a + (b - a) * fraction
    }
    return AudioBuffer(out, targetRate)
}

/** Scales to a peak of 1.0. Silence is returned unchanged rather than amplified into noise. */
fun AudioBuffer.normalized(): AudioBuffer {
    var peak = 0f
    for (sample in samples) peak = max(peak, abs(sample))
    if (peak < 1e-6f || peak in 0.999f..1.001f) return this
    val gain = 1f / peak
    return AudioBuffer(FloatArray(samples.size) { samples[it] * gain }, sampleRate)
}
