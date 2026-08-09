package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where the harmony changes, without deciding what it changed to.
 *
 * Timing and naming are separate problems. A detected harmonic turn is allowed to create an
 * off-grid boundary even when the labeler is uncertain, so anticipated chords can move at the
 * moment they are actually played rather than at the nearest beat.
 *
 * Low-band bass can optionally be supplied. Its pitch-class influence is softly reduced before
 * novelty is measured, because a walking bass line is not by itself a harmonic change. The bass is
 * not deleted: enough remains that a real root change can still contribute when the upper harmony
 * also moves.
 */
object HarmonicNovelty {

    /** How far either side of a moment is compared. Long enough to average out a passing tone. */
    const val DefaultWindowSeconds = 0.4

    /** Two changes closer than this are one change; a chord shorter than it is a grace note. */
    const val DefaultMinimumSeparationSeconds = 0.28

    /**
     * Per-frame novelty in `0..1`: how unlike the near future the recent past is.
     *
     * Running sums keep the comparison linear in recording length. A bass-suppressed chromagram is
     * only twelve floats per frame, so materializing that small feature costs little and avoids
     * repeatedly recalculating the same suppression while the windows slide.
     */
    fun of(
        chroma: Chromagram,
        windowSeconds: Double = DefaultWindowSeconds,
        bassChroma: Chromagram? = null,
    ): FloatArray {
        val original = chroma.frames
        val count = original.size
        val out = FloatArray(count)
        if (count < 4 || chroma.hopSeconds <= 0.0) return out

        val frames = if (bassChroma == null) {
            original
        } else {
            Array(count) { index ->
                suppressBassInfluence(original[index], bassChroma.frames.getOrNull(index))
            }
        }

        val window = (windowSeconds / chroma.hopSeconds).toInt().coerceIn(2, max(2, count / 2))
        val before = FloatArray(Chromagram.PitchClasses)
        val after = FloatArray(Chromagram.PitchClasses)

        for (frame in 0 until window) add(before, frames[frame])
        for (frame in window until minOf(count, window * 2)) add(after, frames[frame])

        for (center in window until count - window) {
            out[center] = 1f - cosine(before, after)
            add(before, frames[center])
            subtract(before, frames[center - window])
            subtract(after, frames[center])
            if (center + window < count) add(after, frames[center + window])
        }
        return out
    }

    /**
     * Frame indices where novelty peaks, thresholded against the local median.
     */
    fun peaks(
        novelty: FloatArray,
        hopSeconds: Double,
        minimumSeparationSeconds: Double = DefaultMinimumSeparationSeconds,
        medianWindow: Int = 32,
        delta: Float = 0.070f,
    ): List<Int> {
        if (novelty.size < 3 || hopSeconds <= 0.0) return emptyList()
        val separation = (minimumSeparationSeconds / hopSeconds).toInt().coerceAtLeast(1)

        val candidates = mutableListOf<Int>()
        val scratch = FloatArray(medianWindow * 2 + 1)
        for (index in 1 until novelty.size - 1) {
            val value = novelty[index]
            if (value < novelty[index - 1] || value < novelty[index + 1]) continue
            var counted = 0
            for (offset in -medianWindow..medianWindow) {
                val at = index + offset
                if (at in novelty.indices) scratch[counted++] = novelty[at]
            }
            val slice = scratch.copyOf(counted)
            slice.sort()
            if (value > slice[counted / 2] + delta) candidates += index
        }

        val kept = mutableListOf<Int>()
        for (candidate in candidates.sortedByDescending { novelty[it] }) {
            if (kept.none { kotlin.math.abs(it - candidate) < separation }) kept += candidate
        }
        return kept.sorted()
    }

    private fun add(into: FloatArray, frame: FloatArray) {
        for (pc in into.indices) into[pc] += frame[pc]
    }

    private fun subtract(from: FloatArray, frame: FloatArray) {
        for (pc in from.indices) from[pc] -= frame[pc]
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (pc in a.indices) {
            dot += a[pc] * b[pc]
            normA += a[pc] * a[pc]
            normB += b[pc] * b[pc]
        }
        val norm = sqrt(normA) * sqrt(normB)
        return if (norm < 1e-6f) 1f else (dot / norm).coerceIn(0f, 1f)
    }
}
