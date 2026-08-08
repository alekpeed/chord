package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where the harmony changes — without deciding what it changed to.
 *
 * This exists because naming and timing are different problems and the harder one was gating the
 * easier one. A chord boundary used to exist only where the decoder changed its mind about the
 * label, so a change the recognizer was too unsure to commit to produced no boundary at all and
 * the highlight simply did not move. On dense material that is most of them.
 *
 * Detecting *that* the harmony moved needs no labels. Comparing the chroma just before a moment
 * with the chroma just after it says plainly whether the pitch content turned over, and it says so
 * whether the chord is a clean triad or something the templates cannot name.
 *
 * This is the diagonal of a self-similarity matrix under a checkerboard kernel, evaluated only
 * where it is read. The full matrix would be the textbook construction and also quadratic: at a
 * 23 ms hop a seven-minute recording is around eighteen thousand frames, so the matrix alone is
 * over a gigabyte, on a device where memory exhaustion has already been a real failure.
 */
object HarmonicNovelty {

    /** How far either side of a moment is compared. Long enough to average out a passing tone. */
    const val DefaultWindowSeconds = 0.4

    /** Two changes closer than this are one change; a chord shorter than it is a grace note. */
    const val DefaultMinimumSeparationSeconds = 0.28

    /**
     * Per-frame novelty in `0..1`: how unlike the near future the recent past is.
     *
     * Running sums, so the window either side costs the same whatever its width.
     */
    fun of(chroma: Chromagram, windowSeconds: Double = DefaultWindowSeconds): FloatArray {
        val frames = chroma.frames
        val count = frames.size
        val out = FloatArray(count)
        if (count < 4 || chroma.hopSeconds <= 0.0) return out

        val window = (windowSeconds / chroma.hopSeconds).toInt().coerceIn(2, max(2, count / 2))
        val before = FloatArray(Chromagram.PitchClasses)
        val after = FloatArray(Chromagram.PitchClasses)

        // Seeded for frame `window`: the two windows meeting there, then slid one frame at a time.
        for (frame in 0 until window) add(before, frames[frame])
        for (frame in window until minOf(count, window * 2)) add(after, frames[frame])

        for (center in window until count - window) {
            out[center] = 1f - cosine(before, after)
            // Slide: the frame at the seam moves from the future into the past, and one more
            // frame joins the future at its far end.
            add(before, frames[center])
            subtract(before, frames[center - window])
            subtract(after, frames[center])
            if (center + window < count) add(after, frames[center + window])
        }
        return out
    }

    /**
     * Frame indices where novelty peaks, thresholded against its own local median.
     *
     * An absolute threshold cannot work: novelty is far higher throughout a busy arrangement than
     * anywhere in a sparse one, and a number that finds changes in the first invents them in the
     * second. The comparison is local for the same reason a chorus should not raise the bar for
     * the verse next to it.
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

        // Strongest first, so when two peaks are too close together the weaker one is the one that
        // loses — rather than whichever happened to come first in time.
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
