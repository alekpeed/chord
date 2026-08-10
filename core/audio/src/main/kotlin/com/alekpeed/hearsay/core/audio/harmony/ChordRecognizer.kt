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

data class KeyContext(val tonicPitchClass: Int, val isMinor: Boolean, val confidence: Float)

/**
 * Reduces low-band pitch influence for change evidence and bass-only transition checks.
 *
 * This must never feed chord-identity scoring. Once a spectrum has been folded into twelve pitch
 * classes, a low D bass and every higher D in the harmony are the same bin. Attenuating that bin in
 * the identity observation can turn Dm7 into its F-major subset or Cmaj7 into Em. The masked copy is
 * useful only for asking whether the non-bass harmony actually moved.
 */
internal fun suppressBassInfluence(
    observed: FloatArray,
    bassObserved: FloatArray?,
    preservedPitchClass: Int? = null,
): FloatArray {
    if (bassObserved == null || bassObserved.isEmpty()) return observed
    val peak = bassObserved.maxOrNull() ?: return observed
    if (peak < BassMaskPresenceThreshold) return observed

    val out = observed.copyOf()
    for (pc in out.indices) {
        val relativeBass = (bassObserved.getOrElse(pc) { 0f } / peak).coerceIn(0f, 1f)
        val maximumSuppression = if (pc == preservedPitchClass) {
            PersistentBassSuppression
        } else {
            BassMaskMaxSuppression
        }
        val attenuation = 1f - maximumSuppression * relativeBass * relativeBass
        out[pc] *= attenuation
    }
    return Chromagram.normalize(out)
}

private const val BassMaskMaxSuppression = 0.90f
private const val PersistentBassSuppression = 0.15f
private const val BassMaskPresenceThreshold = 0.25f

// Synthetic and extracted walking-bass spans stay below 0.10 after low-band reduction, while even
// closely related upper-shell changes exceed 0.20. Keep the boundary between those measured bands.
private const val BassOnlyChangeDistance = 0.18f

/**
 * Chord recognition whose state represents harmonic identity, not every spectral rearrangement.
 *
 * Root/quality are decoded first. Bass and upper extensions are attached only after that identity is
 * stable. This keeps a walking line from becoming a procession of new roots while still allowing a
 * sustained inversion to be written as a slash chord and a sustained tension to be written as b9,
 * #11, 13, and so on.
 */
class ChordRecognizer(
    private val selfTransitionBonus: Float = 1.5f,
    private val relatedTransitionBonus: Float = 0.4f,
    private val noChordThreshold: Float = 0.42f,
    private val emissionSharpness: Float = 7.5f,
    private val slashChords: Boolean = true,
    private val extensionPenalty: Float = 1f,
    private val trace: ChordDecisionTrace? = null,
) {

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
        val persistentRoots = persistentBassRoots(bassObservations)
        val persistentSupport = persistentBassSupport(bassObservations)

        // The raw view supplies frame-level evidence. The bass-reduced view corroborates structural
        // regions and supplies color only after their identity has been decoded.
        val changeObservations = rawObservations.mapIndexed { index, observation ->
            suppressBassInfluence(
                observed = observation,
                bassObserved = bassObservations?.getOrNull(index),
                preservedPitchClass = persistentRoots.getOrNull(index),
            )
        }

        val priors = contextPriors(key)
        val emissions = rawObservations.mapIndexed { index, observation ->
            emissionScores(observation, priors, persistentSupport.getOrNull(index))
        }
        val colorFrames = bassReducedFrames(chroma, bassChroma, spans, persistentRoots)
        // Generate broadly, eliminate aggressively: every candidate was scored above, and any that
        // fails a required musical condition is now removed before decoding chooses among them.
        ChordCandidateGate.eliminateUnsupportedCandidates(
            emissions = emissions,
            spans = spans,
            rawAggregates = rawObservations,
            reducedAggregates = changeObservations,
            frames = chroma.frames,
            persistentBass = persistentSupport,
            hopSeconds = chroma.hopSeconds,
            trace = trace,
        )
        val gatedChangeLikelihood = gateChangeLikelihood(changeLikelihood, changeObservations)
        val path = viterbi(emissions, gatedChangeLikelihood)
        alignDelayedTransitions(path, spans, changeLikelihood)
        decodeStructuralRuns(path, changeObservations, bassObservations, priors, emissions)
        StructuralTransitionGate.confirmStructuralChanges(path, spans, changeObservations, gatedChangeLikelihood, emissions)
        StructuralTransitionGate.collapseSandwichNoise(path, spans, emissions)
        val refined = refineSpans(spans, path, chroma, changeLikelihood)
        val stableChords = ChordColorEnricher.enrich(
            path = path,
            spans = spans,
            colorFrames = colorFrames,
            hopSeconds = chroma.hopSeconds,
            preferFlats = preferFlats,
            enabled = extensionPenalty >= ColorEnrichmentPenaltyFloor,
            trace = trace,
        )

        return refined.mapIndexed { index, (start, end) ->
            val state = path[index]
            val scores = emissions[index]
            val bassVector = bassChroma?.averageBetween(start, end)
            val bass = bassVector?.let(::dominantPitchClass)
            val stableChord = stableChords[index]

            if (state == ChordTemplates.NoChordIndex || stableChord == null) {
                RecognizedChord(start, end, null, confidenceOf(scores, state), emptyList(), bass)
            } else {
                RecognizedChord(
                    startMs = start,
                    endMs = end,
                    chord = stableChord.copy(
                        bass = inversionBass(stableChord, bassVector, preferFlats),
                    ).normalized(),
                    confidence = confidenceOf(scores, state),
                    alternates = alternatesOf(scores, state, preferFlats),
                    bassPitchClass = bass,
                )
            }
        }
    }

    /**
     * A root has to persist into a neighboring analysis span before bass is allowed to influence root
     * identity. A walking line that changes every span therefore remains bass-only evidence.
     */
    private fun persistentBassRoots(bass: List<FloatArray>?): List<Int?> {
        if (bass == null) return emptyList()
        val dominant = bass.map(::dominantPitchClass)
        return dominant.indices.map { index ->
            val current = dominant[index] ?: return@map null
            val agreesBefore = index > 0 && dominant[index - 1] == current
            val agreesAfter = index + 1 < dominant.size && dominant[index + 1] == current
            current.takeIf { agreesBefore || agreesAfter }
        }
    }

    /**
     * Keeps low-band support only when the same pitch class remains present in a neighboring span.
     * A walking bass note that appears for one span therefore cannot steer harmonic identity, while
     * a real root may remain useful even when another chord tone briefly measures louder.
     */
    private fun persistentBassSupport(bass: List<FloatArray>?): List<FloatArray> {
        if (bass == null) return emptyList()
        val relative = bass.map { vector ->
            val peak = vector.maxOrNull() ?: 0f
            if (peak < BassPresenceThreshold) {
                FloatArray(12)
            } else {
                FloatArray(12) { pc -> (vector.getOrElse(pc) { 0f } / peak).coerceIn(0f, 1f) }
            }
        }
        return relative.indices.map { index ->
            FloatArray(12) { pc ->
                val current = relative[index][pc]
                val agreesBefore = index > 0 && relative[index - 1][pc] >= BassPersistenceFloor
                val agreesAfter = index + 1 < relative.size && relative[index + 1][pc] >= BassPersistenceFloor
                current.takeIf { it >= BassPersistenceFloor && (agreesBefore || agreesAfter) } ?: 0f
            }
        }
    }

    /**
     * Novelty can be excited by a bass note even when bass is not allowed to determine the label.
     * It may relax decoder stickiness only when the bass-reduced change observation also moved.
     */
    private fun gateChangeLikelihood(
        requested: FloatArray?,
        observations: List<FloatArray>,
    ): FloatArray? {
        if (requested == null) return null
        val out = requested.copyOf()
        for (index in 1 until minOf(out.size, observations.size)) {
            var dot = 0f
            for (pc in 0 until 12) dot += observations[index - 1][pc] * observations[index][pc]
            val harmonicDistance = (1f - dot).coerceIn(0f, 1f)
            out[index] *= (harmonicDistance / FullChangeDistance).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * Decodes one structural identity for each contiguous region of corroborating upper harmony.
     *
     * The frame decoder still sees full-band chroma so it does not lose a genuine root doubled in
     * the bass. Its individual decisions are not authoritative, though: while the bass-reduced
     * observations remain continuous, they are aggregated and scored once. A walking line is then
     * averaged out instead of selecting a new root on every note. A genuinely short chord remains
     * a separate region as soon as its non-bass shell changes. A candidate the gate eliminated
     * across the run stays eliminated: this pass may not reintroduce what validation rejected.
     */
    @Suppress("LongParameterList")
    private fun decodeStructuralRuns(
        path: IntArray,
        bassReducedObservations: List<FloatArray>,
        bassObservations: List<FloatArray>?,
        priors: FloatArray,
        emissions: List<FloatArray>,
    ) {
        if (bassObservations == null || path.isEmpty()) return
        val limit = minOf(path.size, bassReducedObservations.size, bassObservations.size)
        var runStart = 0
        for (index in 1..limit) {
            val continues = index < limit &&
                observationDistance(bassReducedObservations[index - 1], bassReducedObservations[index]) <=
                BassOnlyChangeDistance
            if (!continues) {
                val movingBass = (runStart until index)
                    .mapNotNull { span -> dominantPitchClass(bassObservations[span]) }
                    .distinct()
                    .size > 1
                if (!movingBass) {
                    runStart = index
                    continue
                }
                val aggregate = FloatArray(Chromagram.PitchClasses)
                for (span in runStart until index) {
                    for (pc in aggregate.indices) aggregate[pc] += bassReducedObservations[span][pc]
                }
                val scores = emissionScores(Chromagram.normalize(aggregate), priors, null)
                val best = bestSurvivingState(scores, emissions, runStart, index)
                for (span in runStart until index) path[span] = best
                runStart = index
            }
        }
    }

    /**
     * The best aggregate score among states the gate left standing over the run. A state whose
     * per-span emissions sit far below the best mean was eliminated for most of the run, and a
     * post-processing pass has no authority to resurrect it.
     */
    private fun bestSurvivingState(
        scores: FloatArray,
        emissions: List<FloatArray>,
        runStart: Int,
        runEnd: Int,
    ): Int {
        val means = FloatArray(ChordTemplates.StateCount)
        var bestMean = Float.NEGATIVE_INFINITY
        for (state in 0 until ChordTemplates.StateCount) {
            var total = 0f
            for (span in runStart until runEnd) total += emissions[span][state]
            means[state] = total / (runEnd - runStart)
            if (means[state] > bestMean) bestMean = means[state]
        }
        var best = -1
        for (state in 0 until ChordTemplates.StateCount) {
            if (means[state] < bestMean - EliminatedRunTolerance) continue
            if (best < 0 || scores[state] > scores[best]) best = state
        }
        return if (best >= 0) best else scores.indices.maxBy { scores[it] }
    }

    /** Per-frame bass reduction lets color persistence be measured instead of inferred from averages. */
    private fun bassReducedFrames(
        chroma: Chromagram,
        bassChroma: Chromagram?,
        spans: List<Pair<Long, Long>>,
        persistentRoots: List<Int?>,
    ): Array<FloatArray> {
        var span = 0
        return Array(chroma.frameCount) { frame ->
            val timeMs = chroma.timeMsOfFrame(frame)
            while (span < spans.lastIndex && spans[span + 1].first <= timeMs) span++
            suppressBassInfluence(
                observed = chroma.frames[frame],
                bassObserved = bassChroma?.frames?.getOrNull(frame),
                preservedPitchClass = persistentRoots.getOrNull(span),
            )
        }
    }

    /** Bass is named only after harmonic identity is settled. */
    private fun inversionBass(
        chord: Chord,
        bassVector: FloatArray?,
        preferFlats: Boolean,
    ): NoteSpelling? {
        if (bassVector == null || !slashChords) return null
        val best = dominantPitchClass(bassVector) ?: return null
        if (best == chord.root.pitchClass) return null
        if (best !in chord.copy(bass = null).pitchClasses()) return null

        val bestValue = bassVector[best]
        val rootEnergy = bassVector.getOrElse(chord.root.pitchClass) { 0f }
        if (bestValue < BassDominanceRatio * max(rootEnergy, 1e-6f)) return null
        return NoteSpelling.fromPitchClass(best, preferFlats)
    }

    private fun contextPriors(key: KeyContext?): FloatArray {
        val priors = FloatArray(ChordTemplates.Candidates.size)
        val scale = key?.let(::scaleOf)
        val strength = ((key?.confidence ?: 0f).coerceIn(0f, 1f)) * KeyPriorStrength

        for ((index, candidate) in ChordTemplates.Candidates.withIndex()) {
            var prior = candidate.template.prior
            if (scale != null && key != null) {
                val degree = Math.floorMod(candidate.root - key.tonicPitchClass, 12)
                val rootFit = when {
                    degree == 0 -> TonicFit
                    degree == 7 -> DominantFit
                    degree in scale -> DiatonicFit
                    else -> ChromaticFit
                }
                prior *= 1f + strength * (rootFit - 1f)
                prior *= structuralScalePrior(candidate, key, scale, strength)
            }
            priors[index] = prior
        }
        return priors
    }

    /**
     * A close spectral tie is broken in favor of the structural shell that fits the estimated key.
     * This is intentionally mild: borrowed/altered harmony can still win on audio evidence.
     */
    private fun structuralScalePrior(
        candidate: ChordTemplates.Candidate,
        key: KeyContext,
        scale: Set<Int>,
        strength: Float,
    ): Float {
        val colorIntervals = mutableSetOf<Int>()
        for (degree in candidate.template.extensions) {
            when (degree) {
                9 -> colorIntervals += 2
                11 -> colorIntervals += 5
                13 -> colorIntervals += 9
            }
        }
        for (alteration in candidate.template.alterations) {
            if (alteration.degree > 5) colorIntervals += alteration.semitonesFromRoot
        }
        val structuralIntervals = candidate.template.intervals.filterNot { it in colorIntervals }
        if (structuralIntervals.isEmpty()) return 1f
        val inKey = structuralIntervals.count { interval ->
            Math.floorMod(candidate.root + interval - key.tonicPitchClass, 12) in scale
        }
        val fraction = inKey.toFloat() / structuralIntervals.size
        return 1f + strength * StructuralScaleStrength * (fraction - 0.75f)
    }

    private fun scaleOf(key: KeyContext): Set<Int> =
        if (key.isMinor) setOf(0, 2, 3, 5, 7, 8, 10) else setOf(0, 2, 4, 5, 7, 9, 11)

    /**
     * Scores candidate identity from the unmasked harmonic observation. A sustained bass root may
     * break a close seventh-versus-subset tie only when the seventh itself is also audible. Passing
     * bass never receives that multiplier, and a plain triad cannot become a seventh from bass alone.
     */
    private fun emissionScores(
        observed: FloatArray,
        priors: FloatArray,
        persistentBassSupport: FloatArray?,
    ): FloatArray {
        val scores = FloatArray(ChordTemplates.StateCount)
        for ((index, candidate) in ChordTemplates.Candidates.withIndex()) {
            var dot = 0f
            for (pc in 0 until 12) {
                val weight = if (extensionPenalty != 1f && isColorTone(candidate, pc)) {
                    extensionPenalty
                } else {
                    1f
                }
                dot += observed[pc] * candidate.vector[pc] * weight
            }
            val unsupportedSeventh = candidate.template.seventh != SeventhType.NONE &&
                !hasSeventhSupport(candidate, observed)
            val evidencePenalty = if (unsupportedSeventh) UnsupportedSeventhPenalty else 0f
            scores[index] = dot * priors[index] - structuralComplexityPenalty(candidate) - evidencePenalty
        }
        applyPersistentBassRootTieBreak(scores, observed, persistentBassSupport)

        var energy = 0f
        for (value in observed) energy += value * value
        scores[ChordTemplates.NoChordIndex] = if (energy < 1e-4f) 1f else noChordThreshold
        return scores
    }

    /**
     * Dense normalized templates can match incidental notes without paying for the extra claim.
     * Triads are the zero-complexity explanation; named sevenths and structurally altered shells
     * must improve the acoustic fit enough to overcome these explicit margins.
     */
    private fun structuralComplexityPenalty(candidate: ChordTemplates.Candidate): Float {
        var penalty = 0f
        if (candidate.template.seventh != SeventhType.NONE) penalty += SeventhComplexityPenalty
        if (candidate.template.alterations.any { it.degree <= 5 }) penalty += StructuralAlterationPenalty
        return penalty
    }

    private fun applyPersistentBassRootTieBreak(
        scores: FloatArray,
        observed: FloatArray,
        persistentBassSupport: FloatArray?,
    ) {
        if (persistentBassSupport == null || persistentBassSupport.isEmpty()) return
        val bestFullBand = ChordTemplates.Candidates.indices.maxOfOrNull { scores[it] } ?: return
        if (bestFullBand <= 0f) return

        var promotedIndex = -1
        var promotedEvidence = 0f
        for ((index, candidate) in ChordTemplates.Candidates.withIndex()) {
            if (scores[index] < bestFullBand * BassTieBreakFloor) continue
            val support = persistentBassSupport.getOrElse(candidate.root) { 0f }
            if (support < BassRootSupportFloor || !hasSeventhSupport(candidate, observed)) continue
            val evidence = support * scores[index]
            if (evidence > promotedEvidence) {
                promotedEvidence = evidence
                promotedIndex = index
            }
        }
        if (promotedIndex >= 0) {
            scores[promotedIndex] = maxOf(scores[promotedIndex], bestFullBand * BassRootPreference)
        }
    }

    private fun hasSeventhSupport(candidate: ChordTemplates.Candidate, observed: FloatArray): Boolean {
        val interval = when (candidate.template.seventh) {
            SeventhType.MINOR -> 10
            SeventhType.MAJOR -> 11
            SeventhType.DIMINISHED -> 9
            SeventhType.NONE -> return false
        }
        val peak = observed.maxOrNull() ?: return false
        if (peak <= 0f) return false
        val pitchClass = Math.floorMod(candidate.root + interval, 12)
        return observed.getOrElse(pitchClass) { 0f } >= peak * SeventhSupportRatio
    }

    /**
     * In Standard and Full detail, the seventh/sixth is part of harmonic identity rather than
     * disposable color. Penalizing it made Dm7 collapse to its F-major subset and Cmaj7 collapse to
     * Em, exactly the opposite of the requested rich-but-stable output. Simple detail still applies
     * its reduction to those tones. Upper extensions and altered tensions remain detail-weighted in
     * every mode and are enriched after the structural identity is stable.
     */
    private fun isColorTone(candidate: ChordTemplates.Candidate, pitchClass: Int): Boolean {
        val template = candidate.template
        val intervals = mutableSetOf<Int>()
        if (extensionPenalty < ColorEnrichmentPenaltyFloor) {
            when (template.seventh) {
                SeventhType.MINOR -> intervals += 10
                SeventhType.MAJOR -> intervals += 11
                SeventhType.DIMINISHED -> intervals += 9
                SeventhType.NONE -> Unit
            }
            if (template.sixth) intervals += 9
        }
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

    /** Only structural identity changes receive their own transition time. */
    private fun alignDelayedTransitions(
        path: IntArray,
        spans: List<Pair<Long, Long>>,
        detectedChangeLikelihood: FloatArray?,
    ) {
        if (detectedChangeLikelihood == null) return
        for (index in 1 until path.size) {
            val previousIdentity = harmonicIdentity(path[index - 1])
            val currentIdentity = harmonicIdentity(path[index])
            if (previousIdentity == currentIdentity || previousIdentity == null || currentIdentity == null) continue
            if ((detectedChangeLikelihood.getOrNull(index) ?: 0f) > 0f) continue

            val detectedIndex = index - 1
            if ((detectedChangeLikelihood.getOrNull(detectedIndex) ?: 0f) <= 0f) continue
            val detectedMs = spans[detectedIndex].first
            val delayedMs = spans[index].first
            if (delayedMs - detectedMs !in 1..DetectedBoundaryAssociationMs) continue

            // The audio changed at the previous boundary, but temporal continuity made the labeler
            // wait one short span before committing. Move the new identity into that span instead
            // of moving timestamps backward across an existing boundary.
            path[detectedIndex] = path[index]
        }
    }

    private fun refineSpans(
        spans: List<Pair<Long, Long>>,
        path: IntArray,
        chroma: Chromagram,
        detectedChangeLikelihood: FloatArray?,
    ): List<Pair<Long, Long>> {
        if (spans.size < 2) return spans
        val starts = LongArray(spans.size) { spans[it].first }
        for (index in 1 until spans.size) {
            if (harmonicIdentity(path[index]) == harmonicIdentity(path[index - 1])) continue
            // A novelty peak is already an audio-measured boundary. Refining it again against
            // uncertain chord templates can drag a correct transition hundreds of milliseconds.
            if ((detectedChangeLikelihood?.getOrNull(index) ?: 0f) > 0f) continue
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
     * Related chords may be cheaper to move between, but a transition is never rewarded more than
     * staying. Genuine changes still win when their emissions and change evidence support them.
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

    /**
     * How natural a move between two chords is, as normalized shared pitch-class content.
     *
     * Raw shared-note counts accidentally reward changes between dense related chords. Jaccard
     * similarity keeps this relationship signal in 0..1, so changing can never become more valuable
     * merely because two candidates contain many of the same notes.
     */
    private fun relatednessMatrix(): Array<FloatArray> {
        cachedRelatedness?.let { return it }
        val states = ChordTemplates.StateCount
        val matrix = Array(states) { FloatArray(states) }
        val pitchSets = ChordTemplates.Candidates.map { candidate ->
            candidate.template.intervals.map { Math.floorMod(candidate.root + it, 12) }.toSet()
        }
        for (from in 0 until states) {
            for (to in 0 until states) {
                matrix[from][to] = if (from == ChordTemplates.NoChordIndex || to == ChordTemplates.NoChordIndex) {
                    0f
                } else {
                    val shared = pitchSets[from].intersect(pitchSets[to]).size
                    val union = pitchSets[from].union(pitchSets[to]).size
                    if (union == 0) 0f else shared.toFloat() / union
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
        const val StructuralScaleStrength = 0.22f
        const val BassTieBreakFloor = 0.86f
        const val BassRootSupportFloor = 0.35f
        const val BassRootPreference = 1.08f
        const val BassPersistenceFloor = 0.25f
        const val SeventhSupportRatio = 0.35f
        const val SeventhComplexityPenalty = 0.025f
        const val UnsupportedSeventhPenalty = 0.075f
        const val StructuralAlterationPenalty = 0.045f
        const val DetectedBoundaryAssociationMs = 350L
        const val ChangeRelief = 0.60f
        const val MinimumChangeCost = 0.15f
        const val FullChangeDistance = 0.12f

        /** Half the gate's elimination penalty: eliminated for most of a run means still eliminated. */
        const val EliminatedRunTolerance = 2f

        const val TonicFit = 1.30f
        const val DominantFit = 1.20f
        const val DiatonicFit = 1.12f
        const val ChromaticFit = 0.80f

        const val AlternateCount = 3
        const val BassPresenceThreshold = 0.25f
        const val BassDominanceRatio = 1.5f

        const val ColorEnrichmentPenaltyFloor = 0.90f

        @Volatile
        var cachedRelatedness: Array<FloatArray>? = null
    }
}

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
