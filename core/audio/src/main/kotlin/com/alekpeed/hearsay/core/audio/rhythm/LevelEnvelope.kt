package com.alekpeed.hearsay.core.audio.rhythm

import com.alekpeed.hearsay.core.audio.dsp.Spectrogram
import kotlin.math.sqrt

/**
 * How loud the recording is, frame by frame.
 *
 * [OnsetEnvelope] measures change; this measures level, and the two disagree exactly where it
 * matters. A pause between an intro and a verse is not a stretch where nothing changes — a reverb
 * tail, room tone and tape hiss all keep the flux alive — it is a stretch where nothing is loud.
 * Only the second reading finds it, which is why an earlier attempt at dropping unsounded beats
 * used flux and changed nothing on a real recording.
 */
object LevelEnvelope {

    /**
     * Root-mean-square over a window centered on each frame.
     *
     * Centered rather than beginning at the frame, to match how [Spectrogram] frames the same
     * audio; a window that begins at its own timestamp reports a level that belongs to the
     * following half-window.
     *
     * The sum slides rather than being recomputed, so this is one pass over the samples and no
     * per-sample allocation. A prefix-sum table would be simpler and would also be eight bytes per
     * sample — around 70 MB on a seven-minute recording, on a device where memory exhaustion has
     * already been a real failure.
     */
    fun of(samples: FloatArray, hopSize: Int, windowSize: Int, frameCount: Int): FloatArray {
        val out = FloatArray(maxOf(0, frameCount))
        if (samples.isEmpty() || frameCount <= 0) return out
        if (hopSize <= 0 || windowSize <= 0) return out

        val half = windowSize / 2
        var sum = 0.0
        var low = 0
        var high = 0

        for (frame in 0 until frameCount) {
            val center = frame.toLong() * hopSize
            val from = (center - half).coerceIn(0L, samples.size.toLong()).toInt()
            val to = (center + half).coerceIn(0L, samples.size.toLong()).toInt()
            while (high < to) {
                sum += samples[high].toDouble() * samples[high]
                high++
            }
            while (low < from) {
                sum -= samples[low].toDouble() * samples[low]
                low++
            }
            val count = high - low
            out[frame] = if (count <= 0) 0f else sqrt(sum / count).toFloat()
        }
        return out
    }
}
