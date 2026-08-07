package com.alekpeed.hearsay.core.audio.rhythm

import com.alekpeed.hearsay.core.audio.dsp.Spectrogram
import kotlin.math.ln
import kotlin.math.max

/**
 * Where energy arrives.
 *
 * Spectral flux — the sum of positive change in each frequency bin between consecutive frames.
 * Only rises count: a note starting is an onset, a note stopping is not.
 */
class OnsetEnvelope(
    val values: FloatArray,
    val hopSeconds: Double,
) {
    val size: Int get() = values.size

    fun timeMsOfFrame(frame: Int): Long = (frame * hopSeconds * 1000).toLong()

    /** Local peaks above an adaptive threshold, returned as frame indices. */
    fun peaks(medianWindow: Int = 8, delta: Float = 0.06f): List<Int> {
        val result = mutableListOf<Int>()
        val window = FloatArray(medianWindow * 2 + 1)
        for (i in values.indices) {
            var count = 0
            for (offset in -medianWindow..medianWindow) {
                val index = i + offset
                if (index in values.indices) window[count++] = values[index]
            }
            val slice = window.copyOf(count)
            slice.sort()
            val threshold = slice[count / 2] + delta
            val isPeak = values[i] > threshold &&
                values[i] >= values.getOrElse(i - 1) { 0f } &&
                values[i] >= values.getOrElse(i + 1) { 0f }
            if (isPeak) result += i
        }
        return result
    }

    companion object {
        fun of(spectrogram: Spectrogram, magnitudes: Array<FloatArray> = spectrogram.frames): OnsetEnvelope {
            val frameCount = magnitudes.size
            val values = FloatArray(frameCount)
            for (frame in 1 until frameCount) {
                var flux = 0f
                val current = magnitudes[frame]
                val previous = magnitudes[frame - 1]
                for (bin in current.indices) {
                    // Log magnitudes, so a quiet passage's onsets weigh as much as a loud one's.
                    val diff = ln(1f + current[bin]) - ln(1f + previous[bin])
                    if (diff > 0) flux += diff
                }
                values[frame] = flux
            }
            return OnsetEnvelope(smoothAndNormalize(values), spectrogram.hopSeconds)
        }

        private fun smoothAndNormalize(values: FloatArray): FloatArray {
            // Subtract a slow-moving local mean so a crescendo does not read as continuous onset.
            val window = 16
            val out = FloatArray(values.size)
            for (i in values.indices) {
                var sum = 0f
                var count = 0
                for (offset in -window..0) {
                    val index = i + offset
                    if (index in values.indices) {
                        sum += values[index]
                        count++
                    }
                }
                out[i] = max(0f, values[i] - sum / max(1, count))
            }
            var peak = 0f
            for (value in out) peak = max(peak, value)
            if (peak > 1e-6f) for (i in out.indices) out[i] /= peak
            return out
        }
    }
}
