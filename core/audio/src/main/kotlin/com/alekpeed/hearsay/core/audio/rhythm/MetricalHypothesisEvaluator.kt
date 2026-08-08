package com.alekpeed.hearsay.core.audio.rhythm

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.audio.harmony.chordChangeStrength
import kotlin.math.abs
import kotlin.math.max

/** A complete musical interpretation of one tempo level, not merely an autocorrelation peak. */
data class MetricalHypothesis(
    val bpm: Float,
    val beatsPerMeasure: Int,
    val downbeatPhase: Int,
    val beatFrames: List<Int>,
    val onsetScore: Float,
    val beatFitScore: Float,
    val accentScore: Float,
    val harmonyScore: Float,
    val structureScore: Float,
    val totalScore: Float,
)

data class MetricalSelection(
    val tempo: TempoEstimate,
    val candidates: List<TempoCandidate>,
    val winner: MetricalHypothesis?,
    val runnerUp: MetricalHypothesis?,
)

/**
 * Ranks tempo, meter, and downbeat jointly.
 *
 * The onset envelope proposes levels; it does not get to declare which one is the tactus. Each
 * serious peak and its neighboring octaves gets a tracked grid, and every plausible meter/phase is
 * scored against beat fit, accent periodicity, harmonic rhythm, and bar-level chroma repetition.
 */
object MetricalHypothesisEvaluator {
    private const val MinBpm = 45f
    private const val MaxBpm = 220f
    private const val MinimumBeats = 12
    private const val MinimumChanges = 3
    private const val ChangeThreshold = 0.05f
    private const val CandidateLimit = 8
    private val Meters = listOf(4, 3, 6)

    /** Below this fraction of the onset tempo, a hypothesis counts as a slower metrical level. */
    private const val SlowerLevelRatio = 0.75f

    /** How decisively a slower level must beat the onset level before it is believed. */
    private const val SlowerLevelMargin = 0.05f

    fun select(
        envelope: OnsetEnvelope,
        chroma: Chromagram,
        onsetWinner: TempoEstimate,
        onsetCandidates: List<TempoCandidate>,
    ): MetricalSelection {
        val seeds = candidateSeeds(onsetWinner, onsetCandidates)
        val hypotheses = seeds.flatMap { seed -> evaluateTempo(envelope, chroma, seed) }
            .sortedByDescending(MetricalHypothesis::totalScore)
        val leader = hypotheses.firstOrNull()
            ?: return MetricalSelection(onsetWinner, onsetCandidates, null, null)
        val winner = requireDecisiveSlowerLevel(hypotheses, leader, onsetWinner)
        val runnerUp = hypotheses.firstOrNull { candidate ->
            abs(candidate.bpm - winner.bpm) > winner.bpm * 0.03f
        }
        val margin = runnerUp?.let { ((winner.totalScore - it.totalScore) / max(winner.totalScore, 1e-6f)) }
            ?.coerceIn(0f, 1f) ?: 1f
        val hasMusicalEvidence = winner.harmonyScore > 0f || winner.accentScore > 0f || winner.structureScore > 0f
        val confidence = if (hasMusicalEvidence) {
            (onsetWinner.confidence * margin).coerceIn(0f, 1f)
        } else {
            onsetWinner.confidence
        }
        val rankedTempos = hypotheses.distinctByApproximateTempo()
        val bestScore = max(rankedTempos.firstOrNull()?.totalScore ?: 0f, 1e-6f)
        val candidates = rankedTempos.take(3).map { hypothesis ->
            TempoCandidate(hypothesis.bpm, (hypothesis.totalScore / bestScore).coerceIn(0f, 1f))
        }
        return MetricalSelection(
            tempo = TempoEstimate(winner.bpm, confidence),
            candidates = candidates,
            winner = winner,
            runnerUp = runnerUp,
        )
    }

    /**
     * A level slower than the onsets proposed has to win decisively, not by a hair.
     *
     * Autocorrelation is direct evidence about periodicity; harmonic rhythm and bar repetition are
     * inference on top of it. When they disagree, the inference should have to be convincing to
     * overturn the measurement — otherwise a near-tie in the musical terms silently halves a tempo
     * the onsets were right about.
     *
     * The two cases this separates are far apart, which is why a margin works where tuning a prior
     * did not. A 65 BPM ballad articulated twice per beat prefers its true tactus by about 10%,
     * carried by a harmonic-change contrast twice that of the double. A genuine 120 BPM progression
     * prefers the half by about 1% — noise, because chords changing every bar at 120 also look
     * periodic at 60. Requiring a clear margin keeps the first and rejects the second.
     */
    private fun requireDecisiveSlowerLevel(
        hypotheses: List<MetricalHypothesis>,
        leader: MetricalHypothesis,
        onsetWinner: TempoEstimate,
    ): MetricalHypothesis {
        if (leader.bpm >= onsetWinner.bpm * SlowerLevelRatio) return leader
        val atOnsetLevel = hypotheses.firstOrNull { candidate ->
            abs(candidate.bpm - onsetWinner.bpm) <= onsetWinner.bpm * 0.06f
        } ?: return leader
        val margin = (leader.totalScore - atOnsetLevel.totalScore) / max(leader.totalScore, 1e-6f)
        return if (margin >= SlowerLevelMargin) leader else atOnsetLevel
    }

    private fun evaluateTempo(
        envelope: OnsetEnvelope,
        chroma: Chromagram,
        seed: CandidateSeed,
    ): List<MetricalHypothesis> {
        val beats = BeatTracker.track(envelope, seed.bpm)
        if (beats.size < MinimumBeats) return emptyList()
        val times = beats.map(envelope::timeMsOfFrame)
        val changes = chordChangeStrength(chroma, times)
        val hasHarmony = changes.count { it >= ChangeThreshold } >= MinimumChanges
        val beatFit = beats.map { envelope.values.getOrElse(it) { 0f } }.average().toFloat().coerceIn(0f, 1f)

        val beatStrengths = beats.map { frame -> envelope.values.getOrElse(frame) { 0f } }

        return Meters.flatMap { meter ->
            (0 until meter).map { phase ->
                val accent = barContrast(beatStrengths, meter, phase)
                val harmony = if (hasHarmony) barContrast(changes.indices.map(changes::get), meter, phase) else 0f
                val structure = barRepetition(chroma, times, meter, phase)
                val total = 0.34f * seed.onsetScore +
                    0.16f * beatFit +
                    0.12f * accent +
                    0.28f * harmony +
                    0.10f * structure
                MetricalHypothesis(
                    bpm = seed.bpm,
                    beatsPerMeasure = meter,
                    downbeatPhase = phase,
                    beatFrames = beats,
                    onsetScore = seed.onsetScore,
                    beatFitScore = beatFit,
                    accentScore = accent,
                    harmonyScore = harmony,
                    structureScore = structure,
                    totalScore = total,
                )
            }
        }
    }

    private fun barContrast(values: List<Float>, meter: Int, phase: Int): Float {
        var onBar = 0f
        var offBar = 0f
        var onCount = 0
        var offCount = 0
        for (index in values.indices) {
            if ((index - phase).mod(meter) == 0) {
                onBar += values[index]
                onCount++
            } else {
                offBar += values[index]
                offCount++
            }
        }
        if (onCount == 0 || offCount == 0) return 0f
        return (onBar / onCount - offBar / offCount).coerceIn(0f, 1f)
    }

    /** How consistently bars find a similar non-neighboring bar elsewhere in the recording. */
    private fun barRepetition(chroma: Chromagram, times: List<Long>, meter: Int, phase: Int): Float {
        val starts = times.indices.filter { (it - phase).mod(meter) == 0 && it + meter < times.size }
        if (starts.size < 4) return 0f
        val bars = starts.map { start -> chroma.averageBetween(times[start], times[start + meter]) }
        var sum = 0f
        var count = 0
        for (i in bars.indices) {
            var best = 0f
            for (j in bars.indices) {
                if (abs(i - j) <= 1) continue
                best = max(best, cosine(bars[i], bars[j]))
            }
            sum += best
            count++
        }
        return if (count > 0) (sum / count).coerceIn(0f, 1f) else 0f
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        var dot = 0f
        for (index in left.indices) dot += left[index] * right.getOrElse(index) { 0f }
        return dot.coerceIn(0f, 1f)
    }

    private fun candidateSeeds(
        winner: TempoEstimate,
        candidates: List<TempoCandidate>,
    ): List<CandidateSeed> {
        val originals = buildList {
            add(CandidateSeed(winner.bpm, 1f))
            addAll(candidates.map { CandidateSeed(it.bpm, it.relativeScore) })
        }
        val expanded = originals.flatMap { seed ->
            listOf(
                seed,
                CandidateSeed(seed.bpm / 2f, seed.onsetScore * 0.94f),
                CandidateSeed(seed.bpm * 2f, seed.onsetScore * 0.94f),
            )
        }.filter { it.bpm in MinBpm..MaxBpm }
            .sortedByDescending(CandidateSeed::onsetScore)

        val distinct = mutableListOf<CandidateSeed>()
        for (seed in expanded) {
            if (distinct.none { abs(it.bpm - seed.bpm) <= seed.bpm * 0.03f }) distinct += seed
            if (distinct.size == CandidateLimit) break
        }
        return distinct
    }

    private fun List<MetricalHypothesis>.distinctByApproximateTempo(): List<MetricalHypothesis> {
        val distinct = mutableListOf<MetricalHypothesis>()
        for (hypothesis in this) {
            if (distinct.none { abs(it.bpm - hypothesis.bpm) <= hypothesis.bpm * 0.03f }) {
                distinct += hypothesis
            }
        }
        return distinct
    }

    private data class CandidateSeed(val bpm: Float, val onsetScore: Float)
}
