package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** One chord decision over one beat span, with what it was competing against. */
data class RecognizedChord(
    val startMs: Long,
    val endMs: Long,
    val chord: Chord?,
    val confidence: Float,
    val alternates: List<ChordAlternate>,
    val bassPitchClass: Int?,
)

data class ChordAlternate(val chord: Chord, val score: Float)

/**
 * The key an analysis settled on, used to break ties between chords that sound alike.
 *
 * @param confidence how much the key estimate is trusted; a weak estimate barely tilts anything.
 */
data class KeyContext(val tonicPitchClass: Int, val isMinor: Boolean, val confidence: Float)

/**
 * Beat-synchronous chord recognition.
 *
 * Chroma is averaged over each beat rather than each frame, which is the single biggest quality
 * difference in this whole file: harmony changes on beats, and averaging over a beat cancels the
 * passing tones that make frame-level recognition flicker.
 *
 * The sequence is then decoded with Viterbi, so the answer is the most likely *progression* rather
 * than a row of independent guesses. Staying on a chord is cheap; moving is expensive; moving to a
 * chord that shares notes with the current one is less expensive than moving to an unrelated one.
 */
class ChordRecognizer(
    private val selfTransitionBonus: Float = 1.5f,
    private val relatedTransitionBonus: Float = 0.4f,
    private val noChordThreshold: Float = 0.42f,
    private val emissionSharpness: Float = 7.5f,
    private val slashChords: Boolean = true,
    private val extensionPenalty: Float = 1f,
) {

    /**
     * @param beatTimesMs boundaries of each analysis span; N boundaries yield N-1 spans.
     * @param bassChroma optional low-band chroma used to name inversions and slash chords.
     * @param key the estimated key, which decides between chords the chroma cannot separate.
     */
    fun recognize(
        chroma: Chromagram,
        beatTimesMs: List<Long>,
        bassChroma: Chromagram? = null,
        preferFlats: Boolean = false,
        key: KeyContext? = null,
    ): List<RecognizedChord> {
        if (beatTimesMs.size < 2) return emptyList()

        val spans = beatTimesMs.zipWithNext()
        val observations = spans.map { (start, end) -> chroma.averageBetween(start, end) }
        val priors = contextPriors(key)
        val emissions = observations.map { emissionScores(it, priors) }
        val path = viterbi(emissions)

        return spans.mapIndexed { index, (start, end) ->
            val state = path[index]
            val scores = emissions[index]
            val bassVector = bassChroma?.averageBetween(start, end)
            val bass = bassVector?.let { dominantPitchClass(it) }

            if (state == ChordTemplates.NoChordIndex) {
                RecognizedChord(start, end, null, confidenceOf(scores, state), emptyList(), bass)
            } else {
                val candidate = ChordTemplates.Candidates[state]
                val root = NoteSpelling.fromPitchClass(candidate.root, preferFlats)

                RecognizedChord(
                    startMs = start,
                    endMs = end,
                    chord = candidate.template.toChord(
                        root = root,
                        bass = inversionBass(candidate, bassVector, preferFlats),
                    ),
                    confidence = confidenceOf(scores, state),
                    alternates = alternatesOf(scores, state, preferFlats),
                    bassPitchClass = bass,
                )
            }
        }
    }

    /**
     * Names an inversion only when the evidence is unambiguous.
     *
     * The low band of a root-position chord still contains its third and fifth, and their partials
     * can easily out-measure the fundamental. Claiming a slash chord on that is worse than saying
     * nothing: it turns a correct C into a wrong C/E on the chart. So a bass note is only named
     * when it clearly beats the root in the low band.
     */
    private fun inversionBass(
        candidate: ChordTemplates.Candidate,
        bassVector: FloatArray?,
        preferFlats: Boolean,
    ): NoteSpelling? {
        if (bassVector == null || !slashChords) return null
        val chordTones = candidate.template.intervals.map { Math.floorMod(candidate.root + it, 12) }.toSet()

        var best = -1
        var bestValue = 0f
        for (pc in bassVector.indices) {
            if (bassVector[pc] > bestValue) {
                bestValue = bassVector[pc]
                best = pc
            }
        }
        if (best < 0 || best == candidate.root || best !in chordTones) return null

        val rootEnergy = bassVector[candidate.root]
        if (bestValue < BassDominanceRatio * max(rootEnergy, 1e-6f)) return null
        if (bestValue < BassPresenceThreshold) return null

        return NoteSpelling.fromPitchClass(best, preferFlats)
    }

    /**
     * How likely each chord is before hearing anything, given the key and the detail wanted.
     *
     * Computed once for the whole song rather than per beat: the key does not change within a run,
     * and this is the tiebreaker the recognizer was missing. Chroma alone cannot separate G6 from
     * Em7 — they are the same four pitch classes — nor Gmaj7 from Bm7, which share three of four.
     * Knowing the piece is in G decides both, and the estimate was already being computed and used
     * only to choose between sharps and flats.
     *
     */
    private fun contextPriors(key: KeyContext?): FloatArray {
        val priors = FloatArray(ChordTemplates.Candidates.size)
        val scale = key?.let { scaleOf(it) }
        // A weak key estimate should barely tilt anything; a confident one should tilt a lot.
        val strength = ((key?.confidence ?: 0f).coerceIn(0f, 1f)) * KeyPriorStrength

        for ((index, candidate) in ChordTemplates.Candidates.withIndex()) {
            var prior = candidate.template.prior

            if (scale != null) {
                val degree = Math.floorMod(candidate.root - key.tonicPitchClass, 12)
                val fit = when {
                    degree == 0 -> TonicFit
                    degree == 7 || degree == 5 -> DominantFit
                    scale.contains(degree) -> DiatonicFit
                    else -> ChromaticFit
                }
                prior *= 1f + strength * (fit - 1f)
            }
            priors[index] = prior
        }
        return priors
    }

    /** Semitones above the tonic that belong to the key. */
    private fun scaleOf(key: KeyContext): Set<Int> =
        if (key.isMinor) setOf(0, 2, 3, 5, 7, 8, 10) else setOf(0, 2, 4, 5, 7, 9, 11)

    /**
     * Cosine similarity against every template, scaled by that template's prior.
     *
     * The sixth or seventh a chord is named for counts for less than its triad, by
     * [extensionPenalty]. That is deliberately not a penalty on the whole chord: damping the match
     * outright makes a real Dm7 lose to plain F, because F major is a subset of Dm7 — the chart
     * gets simpler by getting the root wrong, which is worse than the problem. Damping only the
     * added note leaves root, third and fifth arguing at full strength, so a seventh that is really
     * being played still wins and one resting on a passing tone falls back to its own triad.
     */
    private fun emissionScores(observed: FloatArray, priors: FloatArray): FloatArray {
        val scores = FloatArray(ChordTemplates.StateCount)
        for ((index, candidate) in ChordTemplates.Candidates.withIndex()) {
            var dot = 0f
            for (pc in 0 until 12) {
                val weight = if (extensionPenalty != 1f && candidate.isExtensionTone(pc)) extensionPenalty else 1f
                dot += observed[pc] * candidate.vector[pc] * weight
            }
            scores[index] = dot * priors[index]
        }
        // No chord wins when nothing has any energy or nothing matches anything.
        var energy = 0f
        for (value in observed) energy += value * value
        scores[ChordTemplates.NoChordIndex] =
            if (energy < 1e-4f) 1f else noChordThreshold
        return scores
    }

    private fun viterbi(emissions: List<FloatArray>): IntArray {
        val states = ChordTemplates.StateCount
        val steps = emissions.size
        val delta = Array(steps) { FloatArray(states) }
        val psi = Array(steps) { IntArray(states) }

        for (state in 0 until states) delta[0][state] = emissionSharpness * emissions[0][state]

        val relatedness = relatednessMatrix()

        for (step in 1 until steps) {
            for (state in 0 until states) {
                var bestScore = Float.NEGATIVE_INFINITY
                var bestPrevious = 0
                for (previous in 0 until states) {
                    val transition = when {
                        previous == state -> selfTransitionBonus
                        else -> relatedTransitionBonus * relatedness[previous][state]
                    }
                    val value = delta[step - 1][previous] + transition
                    if (value > bestScore) {
                        bestScore = value
                        bestPrevious = previous
                    }
                }
                delta[step][state] = bestScore + emissionSharpness * emissions[step][state]
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

    /**
     * How natural a move between two chords is, as shared pitch-class content.
     *
     * Cheap and effective: a ii–V shares two notes and scores high, a tritone leap shares little
     * and scores low, so the decoder does not wander into unrelated keys on one ambiguous beat.
     */
    private fun relatednessMatrix(): Array<FloatArray> {
        if (cachedRelatedness != null) return cachedRelatedness!!
        val states = ChordTemplates.StateCount
        val matrix = Array(states) { FloatArray(states) }
        val pitchSets = ChordTemplates.Candidates.map { candidate ->
            candidate.template.intervals.map { Math.floorMod(candidate.root + it, 12) }.toSet()
        }
        for (from in 0 until states) {
            for (to in 0 until states) {
                if (from == ChordTemplates.NoChordIndex || to == ChordTemplates.NoChordIndex) {
                    matrix[from][to] = 0f
                    continue
                }
                val shared = pitchSets[from].intersect(pitchSets[to]).size
                matrix[from][to] = shared.toFloat()
            }
        }
        cachedRelatedness = matrix
        return matrix
    }

    private fun confidenceOf(scores: FloatArray, chosen: Int): Float {
        // Softmax over the field, so confidence reflects how clearly this chord won.
        var maxScore = Float.NEGATIVE_INFINITY
        for (score in scores) maxScore = max(maxScore, score)
        var total = 0.0
        for (score in scores) total += exp((emissionSharpness * (score - maxScore)).toDouble())
        val chosenProbability = exp((emissionSharpness * (scores[chosen] - maxScore)).toDouble()) / total
        return chosenProbability.toFloat().coerceIn(0f, 1f)
    }

    private fun alternatesOf(scores: FloatArray, chosen: Int, preferFlats: Boolean): List<ChordAlternate> =
        scores.withIndex()
            .filter { it.index != chosen && it.index != ChordTemplates.NoChordIndex }
            .sortedByDescending { it.value }
            .take(AlternateCount)
            .map { (index, score) ->
                val candidate = ChordTemplates.Candidates[index]
                ChordAlternate(
                    chord = candidate.template.toChord(NoteSpelling.fromPitchClass(candidate.root, preferFlats)),
                    score = score,
                )
            }

    private fun dominantPitchClass(chroma: FloatArray): Int? {
        var best = -1
        var bestValue = 0f
        for (pc in chroma.indices) {
            if (chroma[pc] > bestValue) {
                bestValue = chroma[pc]
                best = pc
            }
        }
        return best.takeIf { it >= 0 && bestValue > BassPresenceThreshold }
    }

    private companion object {
        /** How far a confident key estimate is allowed to move the odds. */
        const val KeyPriorStrength = 0.9f

        // Relative to 1.0, which is "the key says nothing about this chord".
        const val TonicFit = 1.30f
        const val DominantFit = 1.20f
        const val DiatonicFit = 1.12f
        const val ChromaticFit = 0.80f

        const val AlternateCount = 3
        const val BassPresenceThreshold = 0.25f

        /** How much louder than the root a low note must be before it is called an inversion. */
        const val BassDominanceRatio = 1.5f

        @Volatile
        var cachedRelatedness: Array<FloatArray>? = null
    }
}

/**
 * How much the harmony changes from one span to the next, used to find bar lines.
 * Returned per span so it lines up with the beat list the caller already has.
 */
fun chordChangeStrength(chroma: Chromagram, beatTimesMs: List<Long>): FloatArray {
    if (beatTimesMs.size < 2) return FloatArray(0)
    val vectors = beatTimesMs.zipWithNext().map { (start, end) -> chroma.averageBetween(start, end) }
    val out = FloatArray(vectors.size)
    for (i in vectors.indices) {
        if (i == 0) continue
        var dot = 0f
        for (pc in 0 until 12) dot += vectors[i][pc] * vectors[i - 1][pc]
        out[i] = (1f - dot).coerceIn(0f, 1f)
    }
    return out
}

/**
 * Global key by correlating the average chroma with the Krumhansl–Kessler profiles.
 *
 * The key is advisory. It picks the spelling of accidentals and drives Roman-numeral display; it
 * is never allowed to override the note evidence for an individual chord.
 */
object KeyEstimator {

    private val MajorProfile = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f,
    )
    private val MinorProfile = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f,
    )

    data class Estimate(val tonicPitchClass: Int, val isMinor: Boolean, val confidence: Float)

    fun estimate(chroma: Chromagram): Estimate {
        val average = FloatArray(12)
        for (frame in chroma.frames) for (pc in 0 until 12) average[pc] += frame[pc]
        if (chroma.frameCount > 0) for (pc in 0 until 12) average[pc] /= chroma.frameCount

        var bestScore = Float.NEGATIVE_INFINITY
        var runnerUp = Float.NEGATIVE_INFINITY
        var bestTonic = 0
        var bestMinor = false

        for (tonic in 0 until 12) {
            for (minor in listOf(false, true)) {
                val profile = if (minor) MinorProfile else MajorProfile
                val rotated = FloatArray(12) { profile[Math.floorMod(it - tonic, 12)] }
                val score = pearson(average, rotated)
                if (score > bestScore) {
                    runnerUp = bestScore
                    bestScore = score
                    bestTonic = tonic
                    bestMinor = minor
                } else if (score > runnerUp) {
                    runnerUp = score
                }
            }
        }

        val confidence = ((bestScore - runnerUp) / 0.4f).coerceIn(0f, 1f)
        return Estimate(bestTonic, bestMinor, confidence)
    }

    /**
     * Pearson correlation, not a raw dot product.
     *
     * Subtracting the means is what separates a key from its relative minor: the two profiles share
     * almost all their energy and differ mostly in *shape*, which an uncentered dot product cannot
     * see. Krumhansl and Schmuckler specified correlation for exactly this reason.
     */
    private fun pearson(observed: FloatArray, profile: FloatArray): Float {
        var meanObserved = 0f
        var meanProfile = 0f
        for (i in 0 until 12) {
            meanObserved += observed[i]
            meanProfile += profile[i]
        }
        meanObserved /= 12f
        meanProfile /= 12f

        var covariance = 0f
        var varianceObserved = 0f
        var varianceProfile = 0f
        for (i in 0 until 12) {
            val a = observed[i] - meanObserved
            val b = profile[i] - meanProfile
            covariance += a * b
            varianceObserved += a * a
            varianceProfile += b * b
        }
        val denominator = kotlin.math.sqrt(varianceObserved * varianceProfile)
        return if (denominator < 1e-9f) 0f else covariance / denominator
    }

    /** Keys on the flat side of the circle are conventionally written with flats. */
    fun prefersFlats(tonicPitchClass: Int, isMinor: Boolean): Boolean {
        val flatMajorKeys = setOf(5, 10, 3, 8, 1)
        val flatMinorKeys = setOf(2, 7, 0, 5, 10)
        return if (isMinor) tonicPitchClass in flatMinorKeys else tonicPitchClass in flatMajorKeys
    }
}

internal fun logSafe(value: Float): Float = ln(max(1e-9f, value))
