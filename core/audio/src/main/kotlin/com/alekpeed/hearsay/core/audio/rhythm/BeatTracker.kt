package com.alekpeed.hearsay.core.audio.rhythm

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

data class TempoEstimate(val bpm: Float, val confidence: Float)

/**
 * Tempo by autocorrelation of the onset envelope, biased toward how people actually count.
 *
 * Autocorrelation alone is ambiguous by factors of two — a track at 140 correlates just as well at
 * 70. The log-Gaussian bias around a preferred tempo is what resolves it, and it is the same trick
 * that stops a slow ballad being reported at double time.
 */
object TempoEstimator {

    private const val MinBpm = 50.0
    private const val MaxBpm = 210.0
    private const val PreferredBpm = 120.0
    private const val BiasWidth = 1.0

    fun estimate(envelope: OnsetEnvelope): TempoEstimate {
        val minLag = (60.0 / MaxBpm / envelope.hopSeconds).roundToInt().coerceAtLeast(1)
        val maxLag = (60.0 / MinBpm / envelope.hopSeconds).roundToInt().coerceAtMost(envelope.size - 1)
        if (maxLag <= minLag) return TempoEstimate(PreferredBpm.toFloat(), 0f)

        var bestLag = minLag
        var bestScore = Float.NEGATIVE_INFINITY
        var total = 0.0

        for (lag in minLag..maxLag) {
            var correlation = 0f
            for (i in lag until envelope.size) correlation += envelope.values[i] * envelope.values[i - lag]
            val bpm = 60.0 / (lag * envelope.hopSeconds)
            val bias = ln(bpm / PreferredBpm) / BiasWidth
            val score = correlation * kotlin.math.exp(-0.5 * bias * bias).toFloat()
            total += score.toDouble()
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        val bpm = 60.0 / (bestLag * envelope.hopSeconds)
        // Confidence is how much the winner stands out from the field, not its raw magnitude.
        val mean = (total / (maxLag - minLag + 1)).toFloat()
        val confidence = if (mean > 1e-6f) ((bestScore / mean - 1f) / 4f).coerceIn(0f, 1f) else 0f
        return TempoEstimate(bpm.toFloat(), confidence)
    }
}

/**
 * Dynamic-programming beat tracking.
 *
 * Every frame gets the best cumulative score achievable by treating it as a beat, given a penalty
 * for straying from the expected spacing. Backtracking from the best endpoint yields a globally
 * consistent sequence rather than a greedy one that drifts and never recovers.
 */
object BeatTracker {

    private const val TightnessWeight = 100f
    private const val Alpha = 0.7f

    fun track(envelope: OnsetEnvelope, bpm: Float): List<Int> {
        if (envelope.size < 2 || bpm <= 0f) return emptyList()

        val period = (60.0 / bpm / envelope.hopSeconds).toFloat()
        if (period < 1f) return emptyList()

        val size = envelope.size
        val score = FloatArray(size)
        val backlink = IntArray(size) { -1 }

        val searchStart = (-2 * period).roundToInt()
        val searchEnd = (-period / 2).roundToInt()

        for (i in 0 until size) {
            var bestScore = 0f
            var bestIndex = -1
            for (offset in searchStart..searchEnd) {
                val candidate = i + offset
                if (candidate < 0) continue
                val deviation = ln(-offset / period.toDouble()).toFloat()
                val penalty = TightnessWeight * deviation * deviation
                val value = score[candidate] - penalty
                if (bestIndex == -1 || value > bestScore) {
                    bestScore = value
                    bestIndex = candidate
                }
            }
            score[i] = envelope.values[i] + Alpha * max(0f, bestScore)
            backlink[i] = bestIndex
        }

        // Start the backtrace from a strong beat near the end rather than the literal maximum,
        // which is often a fade-out artefact.
        var endIndex = size - 1
        var bestEnd = Float.NEGATIVE_INFINITY
        val tail = (size * 0.9).toInt()
        for (i in tail until size) {
            if (score[i] > bestEnd) {
                bestEnd = score[i]
                endIndex = i
            }
        }

        val beats = mutableListOf<Int>()
        var cursor = endIndex
        val guard = size + 1
        var steps = 0
        while (cursor >= 0 && steps++ < guard) {
            beats += cursor
            cursor = backlink[cursor]
        }
        beats.reverse()
        return regularize(beats, envelope)
    }

    /**
     * Repairs the two ways the backtrace can misbehave.
     *
     * A strong off-beat transient occasionally wins a step, producing one interval well under the
     * period; and a quiet passage can leave a gap of two or three beats with nothing marked. Both
     * are corrected against the median interval, which is far more robust than the nominal tempo
     * because it reflects what the tracker actually found.
     */
    private fun regularize(beats: List<Int>, envelope: OnsetEnvelope): List<Int> {
        if (beats.size < 3) return beats

        val intervals = beats.zipWithNext { a, b -> b - a }.sorted()
        val median = intervals[intervals.size / 2]
        if (median <= 0) return beats

        val kept = mutableListOf(beats.first())
        for (index in 1 until beats.size) {
            val candidate = beats[index]
            val gap = candidate - kept.last()

            if (gap < TooCloseFraction * median) {
                // Keep whichever of the two has more onset energy behind it.
                val incoming = envelope.values.getOrElse(candidate) { 0f }
                val existing = envelope.values.getOrElse(kept.last()) { 0f }
                if (incoming > existing) kept[kept.lastIndex] = candidate
                continue
            }

            if (gap > TooFarFraction * median) {
                val missing = Math.round(gap.toFloat() / median) - 1
                repeat(missing) {
                    kept += kept.last() + Math.round(gap.toFloat() / (missing + 1))
                }
            }
            kept += candidate
        }
        return kept
    }

    private const val TooCloseFraction = 0.72f
    private const val TooFarFraction = 1.55f
}

/**
 * Which beat is beat one.
 *
 * The bar line is chosen by testing every phase against two pieces of evidence: onsets are louder
 * on a downbeat, and harmony is more likely to change there. Chord change carries more weight than
 * loudness, because a drummer's accent can sit anywhere but a chord change lands on a bar.
 */
object DownbeatEstimator {

    fun estimate(
        beatFrames: List<Int>,
        envelope: OnsetEnvelope,
        chordChangeStrength: FloatArray?,
        beatsPerMeasure: Int,
    ): Int {
        if (beatFrames.isEmpty() || beatsPerMeasure <= 1) return 0

        var bestPhase = 0
        var bestScore = Float.NEGATIVE_INFINITY

        for (phase in 0 until beatsPerMeasure) {
            var score = 0f
            for ((index, frame) in beatFrames.withIndex()) {
                if ((index - phase).mod(beatsPerMeasure) != 0) continue
                score += envelope.values.getOrElse(frame) { 0f }
                if (chordChangeStrength != null) {
                    score += 2.5f * chordChangeStrength.getOrElse(index) { 0f }
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
        }
        return bestPhase
    }

    /**
     * Guesses the meter by asking which grouping puts the most agreement on its bar lines.
     * Four is returned on a tie, because most of the repertoire this app targets is in four.
     */
    fun estimateBeatsPerMeasure(
        beatFrames: List<Int>,
        envelope: OnsetEnvelope,
        chordChangeStrength: FloatArray?,
    ): Int {
        if (beatFrames.size < 8) return 4
        var best = 4
        var bestScore = Float.NEGATIVE_INFINITY
        for (candidate in listOf(4, 3, 6)) {
            val phase = estimate(beatFrames, envelope, chordChangeStrength, candidate)
            var onBar = 0f
            var offBar = 0f
            for ((index, frame) in beatFrames.withIndex()) {
                val strength = envelope.values.getOrElse(frame) { 0f } +
                    2.5f * (chordChangeStrength?.getOrElse(index) { 0f } ?: 0f)
                if ((index - phase).mod(candidate) == 0) onBar += strength else offBar += strength
            }
            val bars = max(1, beatFrames.size / candidate)
            val others = max(1, beatFrames.size - bars)
            // Contrast between bar lines and everything else, normalised by how many of each.
            val contrast = onBar / bars - offBar / others
            // A mild preference for four breaks ties without overriding real evidence.
            val score = contrast * if (candidate == 4) 1.08f else 1f
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }
}

/** How close two tempo readings are, used to decide whether a tempo change is real. */
internal fun tempoAgrees(a: Float, b: Float, tolerance: Float = 0.06f): Boolean =
    abs(a - b) / max(a, b) < tolerance
