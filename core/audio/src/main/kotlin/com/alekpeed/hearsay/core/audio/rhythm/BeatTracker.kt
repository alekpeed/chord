package com.alekpeed.hearsay.core.audio.rhythm

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

data class TempoEstimate(val bpm: Float, val confidence: Float)

/**
 * A tempo the estimator seriously considered, with how it scored against the winner.
 *
 * Carried out of the analysis so the app can show what the decision actually was. Two recordings
 * reported the same wrong tempo while every synthetic fixture measured correctly, and nothing in
 * the product could say how close the right answer had come — a screenshot showed the verdict but
 * not the trial. [relativeScore] is 1 for the winner and the losers' share of its score.
 */
data class TempoCandidate(val bpm: Float, val relativeScore: Float)

/**
 * Tempo through a recording, rather than one number for the whole of it.
 *
 * A single tempo lays the whole beat grid against one period, so a recording that drifts, opens in
 * free time, or changes feel slides progressively out of phase — the beat marker keeps moving at a
 * steady rate while the music does not. That is not a tuning problem: no constant can follow a
 * curve, however well it is chosen.
 *
 * Periods are held in envelope frames rather than BPM because that is what the beat tracker's inner
 * loop needs, and converting once here is cheaper than converting in the loop.
 */
class TempoCurve internal constructor(
    private val periods: FloatArray,
    val hopSeconds: Double,
) {
    val size: Int get() = periods.size

    fun periodAt(frame: Int): Float = periods[frame.coerceIn(0, periods.size - 1)]

    fun bpmAt(frame: Int): Float = periodToBpm(periodAt(frame), hopSeconds)

    /** The tempo of the recording as one number, for the places that still want one. */
    val medianBpm: Float
        get() {
            if (periods.isEmpty()) return 0f
            val sorted = periods.sortedArray()
            return periodToBpm(sorted[sorted.size / 2], hopSeconds)
        }

    /**
     * The curve as spans of near-constant tempo.
     *
     * Adjacent frames almost always agree, so this collapses to a handful of spans for a steady
     * recording and to more for one that genuinely moves. A span per frame would be true and
     * useless.
     */
    fun segments(tolerance: Float = 0.04f): List<TempoSpan> {
        if (periods.isEmpty()) return emptyList()
        val spans = mutableListOf<TempoSpan>()
        var startFrame = 0
        var runBpm = bpmAt(0)

        for (frame in 1 until periods.size) {
            val bpm = bpmAt(frame)
            if (!tempoAgrees(bpm, runBpm, tolerance)) {
                spans += TempoSpan(startFrame, frame, runBpm)
                startFrame = frame
                runBpm = bpm
            }
        }
        spans += TempoSpan(startFrame, periods.size, runBpm)
        return spans
    }

    companion object {
        internal fun periodToBpm(period: Float, hopSeconds: Double): Float =
            if (period > 0f) (60.0 / (period * hopSeconds)).toFloat() else 0f

        /** A curve that never changes, for callers that genuinely have one tempo. */
        fun constant(bpm: Float, hopSeconds: Double, frames: Int): TempoCurve {
            val period = if (bpm > 0f) (60.0 / bpm / hopSeconds).toFloat() else 0f
            return TempoCurve(FloatArray(maxOf(1, frames)) { period }, hopSeconds)
        }
    }
}

/** One stretch of near-constant tempo, in envelope frames. */
data class TempoSpan(val startFrame: Int, val endFrame: Int, val bpm: Float)

/**
 * Tempo by autocorrelation of the onset envelope, biased toward how people actually count.
 *
 * Autocorrelation alone is ambiguous by factors of two — a track at 140 correlates just as well at
 * 70. A log-Gaussian bias around a preferred tempo breaks those ties, and a tracked-grid check
 * corrects the one mistake the bias itself used to cause.
 */
object TempoEstimator {

    private const val MinBpm = 50.0
    private const val MaxBpm = 210.0

    /**
     * Centered at 100, not 120.
     *
     * The center is where ties break, and 120 broke them against slow songs: for a 67 BPM ballad
     * the double at 135 sits far nearer 120 than 67 does, so the bias favored the double by about
     * 17% — more than the correlation margin whenever a recording fills the space between its
     * beats. Two real recordings near 70 and 90 BPM both came back as 135; a bias centered at 100
     * is close to neutral across exactly that contested range, and pop tempo distributions center
     * near 100 anyway.
     */
    private const val PreferredBpm = 100.0
    private const val BiasWidth = 1.0

    fun estimate(envelope: OnsetEnvelope): TempoEstimate = estimateWithCandidates(envelope).first

    /**
     * The estimate plus the tempos it beat, so callers can show the decision rather than assert it.
     */
    fun estimateWithCandidates(envelope: OnsetEnvelope): Pair<TempoEstimate, List<TempoCandidate>> {
        val minLag = (60.0 / MaxBpm / envelope.hopSeconds).roundToInt().coerceAtLeast(1)
        val maxLag = (60.0 / MinBpm / envelope.hopSeconds).roundToInt().coerceAtMost(envelope.size - 1)
        if (maxLag <= minLag) return TempoEstimate(PreferredBpm.toFloat(), 0f) to emptyList()

        val scores = FloatArray(maxLag - minLag + 1)
        var bestLag = minLag
        var bestScore = Float.NEGATIVE_INFINITY
        var total = 0.0

        for (lag in minLag..maxLag) {
            var correlation = 0f
            for (i in lag until envelope.size) correlation += envelope.values[i] * envelope.values[i - lag]
            val score = correlation * countingBias(lag, envelope.hopSeconds)
            scores[lag - minLag] = score
            total += score.toDouble()
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        // The winner has to survive a check against half its rate before it is believed.
        val settled = settleHalfTime(envelope, 60.0 / (bestLag * envelope.hopSeconds))

        // Confidence is how much the winner stands out from the field, tempered by how decisively
        // it survived the half-time check — the one place a towering peak can still be wrong.
        val mean = (total / (maxLag - minLag + 1)).toFloat()
        val prominence = if (mean > 1e-6f) ((bestScore / mean - 1f) / 4f).coerceIn(0f, 1f) else 0f
        val confidence = (prominence * settled.margin).coerceIn(0f, 1f)

        return TempoEstimate(settled.bpm.toFloat(), confidence) to
            topCandidates(scores, minLag, envelope.hopSeconds, bestScore)
    }

    private fun countingBias(lag: Int, hopSeconds: Double): Float {
        val bpm = 60.0 / (lag * hopSeconds)
        val bias = ln(bpm / PreferredBpm) / BiasWidth
        return kotlin.math.exp(-0.5 * bias * bias).toFloat()
    }

    /** The distinct peaks of the score curve, so a near-miss is visible to whoever asks. */
    private fun topCandidates(
        scores: FloatArray,
        minLag: Int,
        hopSeconds: Double,
        bestScore: Float,
    ): List<TempoCandidate> {
        if (bestScore <= 0f) return emptyList()
        val peaks = mutableListOf<Pair<Int, Float>>()
        for (i in 1 until scores.size - 1) {
            if (scores[i] >= scores[i - 1] && scores[i] >= scores[i + 1]) peaks += (minLag + i) to scores[i]
        }
        return peaks.sortedByDescending { it.second }
            .take(CandidateCount)
            .map { (lag, score) ->
                TempoCandidate(
                    bpm = (60.0 / (lag * hopSeconds)).toFloat(),
                    relativeScore = (score / bestScore).coerceIn(0f, 1f),
                )
            }
    }

    private class Settled(val bpm: Double, val margin: Float)

    /**
     * Decides whether the winning tempo is actually counting a subdivision of the real one.
     *
     * A recording that fills the space between its beats correlates almost as well at double time,
     * and a preference curve cannot be trusted to break that tie — moving its center only moves
     * which songs it breaks. What settles it is evidence: lay the winner's own beat grid over the
     * onsets and compare each beat with its neighbor. Real beats all carry energy, so consecutive
     * strengths are comparable; a grid running at twice the music's rate lands every other point
     * between the beats, and consecutive strengths alternate. The grid comes from the same dynamic-
     * programming tracker the analysis uses, which snaps to the onsets — so drift, missing beats
     * and expressive timing are absorbed instead of corrupting the comparison.
     *
     * The consecutive-pair median is deliberately local. A global even/odd split falls apart the
     * first time the tracker inserts or drops a beat, because parity flips for everything after;
     * neighboring pairs cannot be desynchronized that way.
     */
    private fun settleHalfTime(envelope: OnsetEnvelope, bpm: Double): Settled {
        if (bpm / 2 < MinBpm) return Settled(bpm, 1f)

        val beats = BeatTracker.track(envelope, bpm.toFloat())
        if (beats.size < MinBeatsForSettle) return Settled(bpm, 1f)

        val strengths = beats.map { frame ->
            var best = 0f
            for (offset in -1..1) {
                val index = frame + offset
                if (index in envelope.values.indices) best = max(best, envelope.values[index])
            }
            best
        }
        val ratios = strengths.zipWithNext { a, b ->
            val stronger = max(a, b)
            if (stronger > 1e-9f) kotlin.math.min(a, b) / stronger else 1f
        }.sorted()
        val median = ratios[ratios.size / 2]

        return if (median < HalfTimeThreshold) {
            // Every other beat of this grid is not really there: the music is at half this rate.
            Settled(bpm / 2, ((HalfTimeThreshold - median) / HalfTimeThreshold).coerceIn(0.15f, 1f))
        } else {
            Settled(bpm, ((median - HalfTimeThreshold) / (1f - HalfTimeThreshold)).coerceIn(0.15f, 1f))
        }
    }

    /**
     * Below this, alternate beats are too weak to all be beats.
     *
     * Set well under the ~0.6 that a strongly accented but genuine grid produces — a 4/4 click with
     * a loud downbeat must not be halved — and well over the near-zero that landing between a
     * ballad's beats produces.
     */
    private const val HalfTimeThreshold = 0.40f

    private const val MinBeatsForSettle = 12

    private const val CandidateCount = 3

    /**
     * Tempo measured in overlapping windows and then decoded as a path.
     *
     * Each window is autocorrelated on its own, which gives a local reading but also a local
     * mistake: a sparse window will happily report half or double the real tempo. Viterbi over the
     * windows fixes that, because the cost of moving between tempi is the log of their ratio, and
     * an octave jump is an enormous step. So the path follows genuine drift closely and refuses to
     * halve or double unless the evidence overwhelms the penalty.
     *
     * The preference for a human counting speed is applied to the whole path once, through the
     * emission scores, rather than being re-litigated by every window independently.
     */
    fun curve(envelope: OnsetEnvelope, anchorBpm: Float = estimate(envelope).bpm): TempoCurve {
        val minLag = (60.0 / MaxBpm / envelope.hopSeconds).roundToInt().coerceAtLeast(1)
        val maxLag = (60.0 / MinBpm / envelope.hopSeconds).roundToInt().coerceAtMost(envelope.size - 1)
        if (envelope.size < 4 || maxLag <= minLag) {
            return TempoCurve.constant(estimate(envelope).bpm, envelope.hopSeconds, maxOf(1, envelope.size))
        }

        val windowFrames = (WindowSeconds / envelope.hopSeconds).roundToInt().coerceAtLeast(maxLag * 2)
        val hopFrames = (WindowHopSeconds / envelope.hopSeconds).roundToInt().coerceAtLeast(1)

        // Anchored to the whole-recording estimate, which has already survived the half-time check.
        // Windows are free to follow drift inside the band; they are not free to re-decide that the
        // recording is at double or half time, because a window cannot see enough to judge that and
        // the median of window-level mistakes is what the app reports as the tempo.
        val anchorLag = 60.0 / anchorBpm / envelope.hopSeconds
        val lowLag = (anchorLag / DriftRange).roundToInt().coerceAtLeast(minLag)
        val highLag = (anchorLag * DriftRange).roundToInt().coerceAtMost(maxLag)
        val lags = if (highLag > lowLag) (lowLag..highLag).toList() else (minLag..maxLag).toList()

        val windowStarts = mutableListOf<Int>()
        var start = 0
        while (start < envelope.size) {
            windowStarts += start
            start += hopFrames
        }
        if (windowStarts.size < 2) {
            return TempoCurve.constant(estimate(envelope).bpm, envelope.hopSeconds, envelope.size)
        }

        val salience = windowStarts.map { windowStart ->
            salienceIn(envelope, windowStart, windowStart + windowFrames, lags)
        }
        val path = decodeTempoPath(salience, lags)

        // One period per envelope frame, interpolated between window centers so the tempo moves
        // smoothly rather than stepping at window boundaries.
        val periods = FloatArray(envelope.size)
        val centers = windowStarts.map { (it + windowFrames / 2).coerceIn(0, envelope.size - 1) }
        for (frame in 0 until envelope.size) {
            periods[frame] = interpolatePeriod(frame, centers, path, lags)
        }
        return TempoCurve(periods, envelope.hopSeconds)
    }

    /** Autocorrelation over one window, biased toward how people actually count. */
    private fun salienceIn(
        envelope: OnsetEnvelope,
        from: Int,
        until: Int,
        lags: List<Int>,
    ): FloatArray {
        val end = until.coerceAtMost(envelope.size)
        val out = FloatArray(lags.size)
        for ((index, lag) in lags.withIndex()) {
            var correlation = 0f
            var i = from + lag
            while (i < end) {
                correlation += envelope.values[i] * envelope.values[i - lag]
                i++
            }
            out[index] = correlation * countingBias(lag, envelope.hopSeconds)
        }
        // Normalized per window, so a loud passage does not simply outvote a quiet one.
        val peak = out.maxOrNull() ?: 0f
        if (peak > 1e-9f) for (i in out.indices) out[i] /= peak
        return out
    }

    /** Viterbi over tempo states; moving costs the squared log of the ratio. */
    private fun decodeTempoPath(salience: List<FloatArray>, lags: List<Int>): IntArray {
        val steps = salience.size
        val states = lags.size
        val delta = Array(steps) { FloatArray(states) }
        val psi = Array(steps) { IntArray(states) }

        for (state in 0 until states) delta[0][state] = salience[0][state]

        for (step in 1 until steps) {
            for (state in 0 until states) {
                var bestScore = Float.NEGATIVE_INFINITY
                var bestPrevious = 0
                for (previous in 0 until states) {
                    val ratio = ln(lags[state].toDouble() / lags[previous]).toFloat()
                    val value = delta[step - 1][previous] - TempoChangeWeight * ratio * ratio
                    if (value > bestScore) {
                        bestScore = value
                        bestPrevious = previous
                    }
                }
                delta[step][state] = bestScore + salience[step][state]
                psi[step][state] = bestPrevious
            }
        }

        val path = IntArray(steps)
        var best = 0
        for (state in 1 until states) if (delta[steps - 1][state] > delta[steps - 1][best]) best = state
        path[steps - 1] = best
        for (step in steps - 2 downTo 0) {
            best = psi[step + 1][best]
            path[step] = best
        }
        return path
    }

    private fun interpolatePeriod(frame: Int, centers: List<Int>, path: IntArray, lags: List<Int>): Float {
        if (frame <= centers.first()) return lags[path.first()].toFloat()
        if (frame >= centers.last()) return lags[path.last()].toFloat()

        var upper = 1
        while (upper < centers.size && centers[upper] < frame) upper++
        val lower = upper - 1
        val span = (centers[upper] - centers[lower]).toFloat()
        val position = if (span > 0f) (frame - centers[lower]) / span else 0f
        val a = lags[path[lower]].toFloat()
        val b = lags[path[upper]].toFloat()
        return a + (b - a) * position
    }

    /**
     * How far the curve may drift from the whole-recording tempo, as a ratio.
     *
     * Wide enough for real rubato and a band pushing or dragging; far short of the factor of two
     * that would let a window re-decide the metrical level.
     */
    private const val DriftRange = 1.35

    /** Long enough to hold several bars, short enough to notice the tempo moving. */
    private const val WindowSeconds = 6.0
    private const val WindowHopSeconds = 1.5

    /**
     * How reluctant the tempo is to change.
     *
     * High enough that a sparse window cannot drag the path to half or double time, low enough that
     * a genuine ritardando is followed. An octave is a log ratio of 0.69, so the penalty for one is
     * roughly forty times that for a one-percent drift.
     */
    private const val TempoChangeWeight = 90f
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

    fun track(envelope: OnsetEnvelope, bpm: Float): List<Int> =
        track(envelope, TempoCurve.constant(bpm, envelope.hopSeconds, envelope.size))

    /**
     * Lays a beat grid that follows [curve] rather than one fixed period.
     *
     * The only change from the fixed-tempo version is that the expected spacing is read at the
     * frame being scored instead of once at the top. That is the whole difference between a grid
     * that drifts away from a recording and one that stays with it: the deviation penalty is now
     * measured against what the music is doing there, not against an average of the whole song.
     */
    fun track(envelope: OnsetEnvelope, curve: TempoCurve): List<Int> =
        trackDetailed(envelope, curve, levels = null).map(TrackedBeat::frame)

    /**
     * The grid, with beats removed where the recording was not sounding.
     *
     * [levels] is per-frame signal level, not onset flux. Flux measures change, so it is near zero
     * during a sustained decay and cannot separate a rest from a held chord; level says plainly
     * whether anything is there. The first attempt at this used flux and did nothing on a real
     * recording, because a pause on analog tape carries hiss, room tone and a reverb tail while
     * carrying no onsets at all.
     */
    fun trackDetailed(envelope: OnsetEnvelope, curve: TempoCurve, levels: FloatArray?): List<TrackedBeat> {
        val grid = layGrid(envelope, curve)
        return if (levels == null) grid else dropUnsoundedBeats(grid, levels, curve)
    }

    /**
     * Removes beats sitting where the recording is quiet for at least a beat either side.
     *
     * The threshold is a fraction of the recording's own median level rather than an absolute one:
     * "quiet" only means anything relative to how loud this particular recording is, and a mix
     * mastered hot has a noise floor a ballad would call a note.
     */
    private fun dropUnsoundedBeats(
        beats: List<TrackedBeat>,
        levels: FloatArray,
        curve: TempoCurve,
    ): List<TrackedBeat> {
        if (beats.size < 3 || levels.isEmpty()) return beats
        val sorted = levels.sortedArray()
        val median = sorted[sorted.size / 2]
        if (median <= 0f) return beats
        val floor = median * QuietFraction

        val kept = beats.filter { beat ->
            val period = curve.periodAt(beat.frame).roundToInt().coerceAtLeast(1)
            !isQuietAround(levels, beat.frame, period, floor)
        }
        // Never hand back an empty grid: a recording quiet throughout is better served by the grid
        // the tracker found than by nothing at all.
        return if (kept.size >= MinimumGridBeats) kept else beats
    }

    /** Whether the recording stays below [floor] for a beat either side of this frame. */
    private fun isQuietAround(levels: FloatArray, frame: Int, period: Int, floor: Float): Boolean {
        var loudest = 0f
        for (index in (frame - period / 2)..(frame + period / 2)) {
            if (index !in levels.indices) continue
            loudest = max(loudest, levels[index])
        }
        return loudest <= floor
    }

    private fun layGrid(envelope: OnsetEnvelope, curve: TempoCurve): List<TrackedBeat> {
        if (envelope.size < 2) return emptyList()
        if (curve.periodAt(0) < 1f) return emptyList()

        val size = envelope.size
        val score = FloatArray(size)
        val backlink = IntArray(size) { -1 }

        for (i in 0 until size) {
            val period = curve.periodAt(i)
            if (period < 1f) continue
            val searchStart = (-2 * period).roundToInt()
            val searchEnd = (-period / 2).roundToInt()

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
        // which is often a fade-out artifact.
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
        return regularize(beats, envelope, curve)
    }

    /**
     * Repairs the two ways the backtrace can misbehave.
     *
     * A strong off-beat transient occasionally wins a step, producing one interval well under the
     * period; and a quiet passage can leave a gap of two or three beats with nothing marked. Both
     * are corrected against the median interval, which is far more robust than the nominal tempo
     * because it reflects what the tracker actually found.
     *
     * A gap is only filled when the recording carried on through it. The tracker cannot tell a
     * passage it under-detected from a passage where nothing is playing, so filling every gap
     * invents beats across rests: a pause between an intro and a verse became bars of counted
     * silence, and the bar numbers marched on through it. Filled beats are marked undetected,
     * because a beat nobody played is an inference and should not be presented as an observation.
     */
    private fun regularize(
        beats: List<Int>,
        envelope: OnsetEnvelope,
        curve: TempoCurve,
    ): List<TrackedBeat> {
        if (beats.size < 3) return beats.map { TrackedBeat(it, detected = true) }

        val floor = silenceFloor(envelope)
        val kept = mutableListOf(TrackedBeat(beats.first(), detected = true))

        for (index in 1 until beats.size) {
            val candidate = beats[index]
            val gap = candidate - kept.last().frame
            // The expected spacing is read where the gap is, not averaged over the recording. A
            // global median would repair a gap in a slow passage using a fast passage's spacing,
            // which is how a filled gap reintroduces exactly the drift this is meant to remove.
            val median = curve.periodAt(kept.last().frame).roundToInt().coerceAtLeast(1)

            if (gap < TooCloseFraction * median) {
                // Keep whichever of the two has more onset energy behind it.
                val incoming = envelope.values.getOrElse(candidate) { 0f }
                val existing = envelope.values.getOrElse(kept.last().frame) { 0f }
                if (incoming > existing) kept[kept.lastIndex] = TrackedBeat(candidate, detected = true)
                continue
            }

            if (gap > TooFarFraction * median) {
                val missing = Math.round(gap.toFloat() / median) - 1
                if (missing in 1..MaxFilledBeats && carriedOn(envelope, kept.last().frame, candidate, floor)) {
                    val step = Math.round(gap.toFloat() / (missing + 1))
                    repeat(missing) { kept += TrackedBeat(kept.last().frame + step, detected = false) }
                }
            }
            kept += TrackedBeat(candidate, detected = true)
        }
        return kept
    }

    /**
     * Whether the recording was still sounding between two beats.
     *
     * Measured against the recording's own quiet passages rather than an absolute level: the onset
     * envelope is normalized, so a ballad's noise floor is nothing like a dense mix's. A rest is
     * silent relative to the piece it sits in.
     */
    private fun carriedOn(envelope: OnsetEnvelope, from: Int, to: Int, floor: Float): Boolean {
        if (to - from < 2) return true
        var sounding = 0
        var counted = 0
        for (frame in (from + 1) until to) {
            if (frame !in envelope.values.indices) continue
            counted++
            if (envelope.values[frame] > floor) sounding++
        }
        if (counted == 0) return true
        return sounding.toFloat() / counted >= SoundingFraction
    }

    /** The level below which this recording is, for its own purposes, quiet. */
    private fun silenceFloor(envelope: OnsetEnvelope): Float {
        if (envelope.size == 0) return AbsoluteSilenceFloor
        val sorted = envelope.values.sortedArray()
        val low = sorted[(sorted.size * SilenceQuantile).toInt().coerceIn(0, sorted.size - 1)]
        return maxOf(low, AbsoluteSilenceFloor)
    }

    private const val TooCloseFraction = 0.72f
    private const val TooFarFraction = 1.55f

    /**
     * How many beats may be invented to bridge one gap.
     *
     * Beyond this the claim stops being a repair and becomes a fabricated passage. A long gap the
     * music did play through is still bridged, in stages, by the beats the tracker did find.
     */
    private const val MaxFilledBeats = 3

    /** Fraction of a gap's frames that must be above the floor for the music to count as playing. */
    private const val SoundingFraction = 0.35f

    /** Quantile of the recording's own envelope taken as its quiet level. */
    private const val SilenceQuantile = 0.30

    /** A normalized envelope this low is silence in any recording. */
    private const val AbsoluteSilenceFloor = 0.02f

    /** Below this many surviving beats, keeping the original grid beats handing back nothing. */
    private const val MinimumGridBeats = 8

    /**
     * A frame below this fraction of the recording's median level counts as not sounding.
     *
     * Low enough that a quiet passage, a fade, or a sustained decay is still music; high enough
     * that tape hiss and room tone in a genuine rest fall under it.
     */
    private const val QuietFraction = 0.10f
}

/** A beat in the grid, and whether the recording actually put anything there. */
data class TrackedBeat(val frame: Int, val detected: Boolean)

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
            // Contrast between bar lines and everything else, normalized by how many of each.
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
