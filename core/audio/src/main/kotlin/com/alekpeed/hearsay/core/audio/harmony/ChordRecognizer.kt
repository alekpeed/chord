package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** One chord decision over one analysis span, with what it was competing against. */
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
 * Reduces the pitch-class energy that can be attributed to the low-band bass track.
 *
 * Bass is useful after a chord is known: it tells us whether to write C/E, and it can be shown as a
 * moving line of its own. It is dangerous as a chord-identity vote. A walking A underneath an
 * unchanged C-major sonority must not turn the whole chord into Am just because Am is a close
 * pitch-class match. The strongest bass pitch is therefore retained, but at only a fraction of its
 * original influence. Other low-band pitch classes are attenuated in proportion to their strength.
 *
 * The transform is deliberately soft rather than a hard notch. A rootless voicing can still use the
 * bass as evidence, but the bass cannot dominate the upper harmonic structure.
 */
internal fun suppressBassInfluence(observed: FloatArray, bassObserved: FloatArray?): FloatArray {
    if (bassObserved == null || bassObserved.isEmpty()) return observed
    val peak = bassObserved.maxOrNull() ?: return observed
    if (peak < BassMaskPresenceThreshold) return observed

    val out = observed.copyOf()
    for (pc in out.indices) {
        val relativeBass = (bassObserved.getOrElse(pc) { 0f } / peak).coerceIn(0f, 1f)
        val attenuation = 1f - BassMaskMaxSuppression * relativeBass * relativeBass
        out[pc] *= attenuation
    }
    return Chromagram.normalize(out)
}

private const val BassMaskMaxSuppression = 0.65f
private const val BassMaskPresenceThreshold = 0.25f

/**
 * Beat/change-synchronous chord recognition.
 *
 * The decoder answers two different questions separately:
 *
 * 1. What is the stable harmonic identity (root, quality, seventh/suspension)?
 * 2. What color is sounding on top of that identity (9, b9, #11, 13, etc.)?
 *
 * Bass is not allowed to answer question 1 by itself. It is tracked separately and can become a
 * slash bass after the harmony is known. Color is also prevented from becoming a fake chord change:
 * C7 -> C9 -> C7 inside one sustained dominant harmony is one harmonic region whose richest stable
 * color is named from the whole region.
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
     * @param bassChroma low-band chroma. It is suppressed for chord identity and retained for bass/slash naming.
     * @param key the estimated key, which decides between chords the chroma cannot separate.
     * @param changeLikelihood per span, how strongly the audio says the harmony turned over at its start.
     */
    fun recognize(
        chroma: Chromagram,
        beatTimesMs: List<Long>,
        bassChroma: Chromagram? = null,
        preferFlats: Boolean = false,
        key: KeyContext? = null,
        changeLikelihood: FloatArray? = null,
    ): List<RecognizedChord> {
        if (beatTimesMs.size < 2) return emptyList()

        val spans = beatTimesMs.zipWithNext()
        val rawObservations = spans.map { (start, end) -> chroma.averageBetween(start, end) }
        val bassObservations = bassChroma?.let { low ->
            spans.map { (start, end) -> low.averageBetween(start, end) }
        }
        val observations = rawObservations.mapIndexed { index, observation ->
            suppressBassInfluence(observation, bassObservations?.getOrNull(index))
        }

        val priors = contextPriors(key)
        val emissions = observations.map { observation -> emissionScores(observation, priors) }
        val path = viterbi(emissions, changeLikelihood)
        val refined = refineSpans(spans, path, chroma)
        val stableChords = enrichHarmonicRuns(path, observations, preferFlats)

        return refined.mapIndexed { index, (start, end) ->
            val state = path[index]
            val scores = emissions[index]
            val bassVector = bassChroma?.averageBetween(start, end)
            val bass = bassVector?.let { dominantPitchClass(it) }
            val stableChord = stableChords[index]

            if (state == ChordTemplates.NoChordIndex || stableChord == null) {
                RecognizedChord(start, end, null, confidenceOf(scores, state), emptyList(), bass)
            } else {
                val namedBass = inversionBass(stableChord, bassVector, preferFlats)
                RecognizedChord(
                    startMs = start,
                    endMs = end,
                    chord = stableChord.copy(bass = namedBass).normalized(),
                    confidence = confidenceOf(scores, state),
                    alternates = alternatesOf(scores, state, preferFlats),
                    bassPitchClass = bass,
                )
            }
        }
    }

    /**
     * Names an inversion only after the chord identity is known.
     *
     * A non-chord walking tone remains available in [RecognizedChord.bassPitchClass] and in the
     * dedicated bass track, but it does not rewrite the chord and it is not forced into a slash name.
     */
    private fun inversionBass(
        chord: Chord,
        bassVector: FloatArray?,
        preferFlats: Boolean,
    ): NoteSpelling? {
        if (bassVector == null || !slashChords) return null
        val chordTones = chord.copy(bass = null).pitchClasses()

        var best = -1
        var bestValue = 0f
        for (pc in bassVector.indices) {
            if (bassVector[pc] > bestValue) {
                bestValue = bassVector[pc]
                best = pc
            }
        }
        if (best < 0 || best == chord.root.pitchClass || best !in chordTones) return null

        val rootEnergy = bassVector.getOrElse(chord.root.pitchClass) { 0f }
        if (bestValue < BassDominanceRatio * max(rootEnergy, 1e-6f)) return null
        if (bestValue < BassPresenceThreshold) return null

        return NoteSpelling.fromPitchClass(best, preferFlats)
    }

    /** How likely each chord is before hearing anything, given the estimated key. */
    private fun contextPriors(key: KeyContext?): FloatArray {
        val priors = FloatArray(ChordTemplates.Candidates.size)
        val scale = key?.let { scaleOf(it) }
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

    private fun scaleOf(key: KeyContext): Set<Int> =
        if (key.isMinor) setOf(0, 2, 3, 5, 7, 8, 10) else setOf(0, 2, 4, 5, 7, 9, 11)

    /** Cosine similarity against every template, after bass influence has already been reduced. */
    private fun emissionScores(observed: FloatArray, priors: FloatArray): FloatArray {
        val scores = FloatArray(ChordTemplates.StateCount)
        for ((index, candidate) in ChordTemplates.Candidates.withIndex()) {
            var dot = 0f
            for (pc in 0 until 12) {
                val weight = if (extensionPenalty != 1f && isColorTone(candidate, pc)) extensionPenalty else 1f
                dot += observed[pc] * candidate.vector[pc] * weight
            }
            scores[index] = dot * priors[index]
        }

        var energy = 0f
        for (value in observed) energy += value * value
        scores[ChordTemplates.NoChordIndex] = if (energy < 1e-4f) 1f else noChordThreshold
        return scores
    }

    /** Whether this pitch class is harmonic color rather than the chord's structural shell. */
    private fun isColorTone(candidate: ChordTemplates.Candidate, pitchClass: Int): Boolean {
        val template = candidate.template
        val intervals = mutableSetOf<Int>()
        when (template.seventh) {
            SeventhType.MINOR -> intervals += 10
            SeventhType.MAJOR -> intervals += 11
            SeventhType.DIMINISHED -> intervals += 9
            SeventhType.NONE -> Unit
        }
        if (template.sixth) intervals += 9
        for (degree in template.extensions) {
            when (degree) {
                9 -> intervals += 2
                11 -> intervals += 5
                13 -> intervals += 9
            }
        }
        for (alteration in template.alterations) {
            if (alteration.degree > 5) intervals += alteration.semitonesFromRoot
        }
        return intervals.any { Math.floorMod(candidate.root + it, 12) == pitchClass }
    }

    /**
     * Chord color is named from the whole stable harmonic run, not independently on every beat.
     *
     * This is what lets the chart be both stable and colorful. A ninth that is genuinely present
     * through a C7 region can produce C9/C7b9/C7#11/etc.; a one-frame melody note cannot create a
     * new chord row, and changing bass notes cannot change the root because bass was already reduced
     * before the path was decoded.
     */
    private fun enrichHarmonicRuns(
        path: IntArray,
        observations: List<FloatArray>,
        preferFlats: Boolean,
    ): List<Chord?> {
        val out = MutableList<Chord?>(path.size) { null }
        var start = 0
        while (start < path.size) {
            val identity = harmonicIdentity(path[start])
            var end = start + 1
            while (end < path.size && harmonicIdentity(path[end]) == identity) end++

            if (identity != null) {
                val aggregate = FloatArray(12)
                for (index in start until end) {
                    for (pc in aggregate.indices) aggregate[pc] += observations[index][pc]
                }
                val normalized = Chromagram.normalize(aggregate)
                val candidate = ChordTemplates.Candidates[path[start]]
                val root = NoteSpelling.fromPitchClass(candidate.root, preferFlats)
                val base = candidate.template.toChord(root).copy(
                    extensions = emptySet(),
                    alterations = candidate.template.alterations.filter { it.degree <= 5 }.toSet(),
                    additions = emptySet(),
                    bass = null,
                ).normalized()
                val colored = if (extensionPenalty >= FullColorPenaltyFloor) {
                    addStableColor(base, normalized)
                } else {
                    base
                }
                for (index in start until end) out[index] = colored
            }
            start = end
        }
        return out
    }

    /** Structural identity deliberately ignores upper extensions and altered tensions. */
    private data class HarmonicIdentity(
        val root: Int,
        val quality: ChordQuality,
        val seventh: SeventhType,
        val sixth: Boolean,
        val suspensions: Set<Int>,
        val structuralAlterations: Set<Alteration>,
    )

    private fun harmonicIdentity(state: Int): HarmonicIdentity? {
        if (state == ChordTemplates.NoChordIndex) return null
        val candidate = ChordTemplates.Candidates[state]
        return HarmonicIdentity(
            root = candidate.root,
            quality = candidate.template.quality,
            seventh = candidate.template.seventh,
            sixth = candidate.template.sixth,
            suspensions = candidate.template.suspensions,
            structuralAlterations = candidate.template.alterations.filter { it.degree <= 5 }.toSet(),
        )
    }

    /**
     * Adds the strongest stable color in each extension family.
     *
     * Several colors can coexist: for example a dominant region can become C9#11 or C7b9b13 when
     * those pitch classes remain present across the region. The threshold is tied to the structural
     * chord energy, so a quiet but sustained tension can be named while broadband leakage is ignored.
     */
    private fun addStableColor(base: Chord, observed: FloatArray): Chord {
        val structural = base.copy(
            extensions = emptySet(),
            alterations = base.alterations.filter { it.degree <= 5 }.toSet(),
            additions = emptySet(),
            bass = null,
        )
        val structuralPcs = structural.pitchClasses()
        val structuralLevel = structuralPcs
            .map { observed.getOrElse(it) { 0f } }
            .average()
            .toFloat()
            .coerceAtLeast(1e-6f)
        val threshold = maxOf(ColorAbsoluteFloor, structuralLevel * ColorRelativeFloor)

        val root = base.root.pitchClass
        val extensions = base.extensions.toMutableSet()
        val additions = base.additions.toMutableSet()
        val alterations = base.alterations.filter { it.degree <= 5 }.toMutableSet()

        fun support(interval: Int): Float = observed[Math.floorMod(root + interval, 12)]
        fun choose(options: List<Pair<Int, Any>>): Any? = options
            .filter { support(it.first) >= threshold }
            .maxByOrNull { support(it.first) }
            ?.second

        if (base.seventh != SeventhType.NONE) {
            val ninthOptions = buildList<Pair<Int, Any>> {
                add(1 to Alteration.FLAT_NINE)
                add(2 to 9)
                if (base.quality == ChordQuality.MAJOR || base.quality == ChordQuality.SUSPENDED) {
                    add(3 to Alteration.SHARP_NINE)
                }
            }
            when (val ninth = choose(ninthOptions)) {
                is Int -> extensions += ninth
                is Alteration -> alterations += ninth
            }

            val eleventhOptions = buildList<Pair<Int, Any>> {
                if (base.quality != ChordQuality.SUSPENDED || 4 !in base.suspensions) add(5 to 11)
                if (base.quality != ChordQuality.DIMINISHED) add(6 to Alteration.SHARP_ELEVEN)
            }
            when (val eleventh = choose(eleventhOptions)) {
                is Int -> extensions += eleventh
                is Alteration -> alterations += eleventh
            }

            if (!base.sixth) {
                val thirteenthOptions = buildList<Pair<Int, Any>> {
                    if (base.quality != ChordQuality.AUGMENTED) add(8 to Alteration.FLAT_THIRTEEN)
                    add(9 to 13)
                }
                when (val thirteenth = choose(thirteenthOptions)) {
                    is Int -> extensions += thirteenth
                    is Alteration -> alterations += thirteenth
                }
            }
        } else if (base.quality == ChordQuality.MAJOR || base.quality == ChordQuality.MINOR) {
            // Without a seventh the same notes are additions rather than 9/11 extensions.
            if (support(2) >= threshold) additions += 9
            if (support(5) >= threshold) additions += 11
        }

        return base.copy(
            extensions = extensions,
            additions = additions,
            alterations = alterations,
        ).normalized()
    }

    /** Moves each true harmonic-identity change to where the audio changes, not to the nearest beat. */
    private fun refineSpans(
        spans: List<Pair<Long, Long>>,
        path: IntArray,
        chroma: Chromagram,
    ): List<Pair<Long, Long>> {
        if (spans.size < 2) return spans
        val starts = LongArray(spans.size) { spans[it].first }
        for (index in 1 until spans.size) {
            if (harmonicIdentity(path[index]) == harmonicIdentity(path[index - 1])) continue
            starts[index] = bestSplitMs(
                chroma = chroma,
                fromMs = (spans[index - 1].first + spans[index - 1].second) / 2,
                toMs = (spans[index].first + spans[index].second) / 2,
                before = path[index - 1],
                after = path[index],
                fallbackMs = spans[index].first,
            )
        }
        return List(spans.size) { index ->
            starts[index] to if (index + 1 < spans.size) starts[index + 1] else spans.last().second
        }
    }

    private fun bestSplitMs(
        chroma: Chromagram,
        fromMs: Long,
        toMs: Long,
        before: Int,
        after: Int,
        fallbackMs: Long,
    ): Long {
        val first = ((fromMs / 1000.0) / chroma.hopSeconds).toInt().coerceIn(0, chroma.frameCount - 1)
        val last = ((toMs / 1000.0) / chroma.hopSeconds).toInt().coerceIn(first, chroma.frameCount - 1)
        if (last - first < 2) return fallbackMs

        val gridSplit = ((fallbackMs / 1000.0) / chroma.hopSeconds).toInt().coerceIn(first + 1, last - 1)
        var score = 0f
        for (frame in first until last) score += frameMatch(chroma.frames[frame], after)
        var gridScore = Float.NEGATIVE_INFINITY
        var bestSplit = gridSplit
        var bestScore = Float.NEGATIVE_INFINITY
        for (split in first + 1 until last) {
            score += frameMatch(chroma.frames[split - 1], before) - frameMatch(chroma.frames[split - 1], after)
            if (split == gridSplit) gridScore = score
            if (score > bestScore) {
                bestScore = score
                bestSplit = split
            }
        }
        return chroma.timeMsOfFrame(if (bestScore > gridScore) bestSplit else gridSplit)
    }

    private fun frameMatch(frame: FloatArray, state: Int): Float {
        if (state == ChordTemplates.NoChordIndex) return noChordThreshold
        val candidate = ChordTemplates.Candidates[state]
        var dot = 0f
        for (pc in 0 until 12) {
            val weight = if (extensionPenalty != 1f && isColorTone(candidate, pc)) extensionPenalty else 1f
            dot += frame[pc] * candidate.vector[pc] * weight
        }
        return dot
    }

    /**
     * Finds the best progression while guaranteeing that changing labels is never rewarded merely
     * for sharing notes.
     *
     * The old raw shared-note bonus could exceed the self-transition bonus (four shared notes made
     * 0.4 * 4 = 1.6 versus 1.5 for staying). Jaccard normalization fixed that arithmetic but made
     * legitimate close-voiced changes too expensive. The correct invariant is simpler: relatedness
     * may make a transition cheap, but it must remain at least [MinimumChangeCost] worse than staying
     * unless the emissions themselves support the new harmony.
     */
    private fun viterbi(emissions: List<FloatArray>, changeLikelihood: FloatArray?): IntArray {
        val states = ChordTemplates.StateCount
        val steps = emissions.size
        val delta = Array(steps) { FloatArray(states) }
        val psi = Array(steps) { IntArray(states) }

        for (state in 0 until states) delta[0][state] = emissionSharpness * emissions[0][state]
        val relatedness = relatednessMatrix()

        for (step in 1 until steps) {
            val change = changeLikelihood?.getOrNull(step)?.coerceIn(0f, 1f) ?: 0f
            val stickiness = selfTransitionBonus * (1f - ChangeRelief * change)
            val maximumChangeBonus = maxOf(0f, stickiness - MinimumChangeCost)

            for (state in 0 until states) {
                var bestScore = Float.NEGATIVE_INFINITY
                var bestPrevious = 0
                for (previous in 0 until states) {
                    val transition = if (previous == state) {
                        stickiness
                    } else {
                        minOf(relatedTransitionBonus * relatedness[previous][state], maximumChangeBonus)
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

    /** Raw shared pitch classes are useful as a transition similarity once their bonus is capped. */
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
                } else {
                    matrix[from][to] = pitchSets[from].intersect(pitchSets[to]).size.toFloat()
                }
            }
        }
        cachedRelatedness = matrix
        return matrix
    }

    private fun confidenceOf(scores: FloatArray, chosen: Int): Float {
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
        const val KeyPriorStrength = 0.9f
        const val ChangeRelief = 0.60f
        const val MinimumChangeCost = 0.15f

        const val TonicFit = 1.30f
        const val DominantFit = 1.20f
        const val DiatonicFit = 1.12f
        const val ChromaticFit = 0.80f

        const val AlternateCount = 3
        const val BassPresenceThreshold = 0.25f
        const val BassDominanceRatio = 1.5f

        /** Color enrichment is intentionally reserved for the FULL-detail recognizer. */
        const val FullColorPenaltyFloor = 0.99f
        const val ColorAbsoluteFloor = 0.18f
        const val ColorRelativeFloor = 0.42f

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

/** Global key by correlating the average chroma with the Krumhansl-Kessler profiles. */
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

    fun prefersFlats(tonicPitchClass: Int, isMinor: Boolean): Boolean {
        val flatMajorKeys = setOf(5, 10, 3, 8, 1)
        val flatMinorKeys = setOf(2, 7, 0, 5, 10)
        return if (isMinor) tonicPitchClass in flatMinorKeys else tonicPitchClass in flatMajorKeys
    }
}

internal fun logSafe(value: Float): Float = ln(max(1e-9f, value))
