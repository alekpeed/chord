package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.model.music.SeventhType
import kotlin.math.max

/**
 * Why one candidate lived or died over one span. Debug and test instrumentation only — the normal
 * user interface never sees these, but a test can assert on exactly which stage removed a chord.
 */
data class CandidateVerdict(
    val candidateName: String,
    val rootSupport: Float,
    val rootPass: Boolean,
    val definingSupport: Float,
    val definingPass: Boolean,
    val shellSupport: Float,
    val shellPass: Boolean,
    val seventhPersistence: Float,
    val seventhPass: Boolean,
) {
    val eliminated: Boolean get() = !(rootPass && definingPass && shellPass && seventhPass)
}

data class SpanEliminationReport(
    val spanIndex: Int,
    val startMs: Long,
    val endMs: Long,
    /** False when every candidate failed and elimination stood down rather than empty the chart. */
    val eliminationApplied: Boolean,
    val verdicts: List<CandidateVerdict>,
)

/** Receives elimination and color decisions. Implemented by tests and debug tooling, never the UI. */
interface ChordDecisionTrace {
    fun onSpan(report: SpanEliminationReport) {}
    fun onColor(startMs: Long, endMs: Long, decision: String) {}
}

/** The tone evidence one span offers: what is present on average, and what persists frame to frame. */
internal class SpanEvidence(
    val rawAggregate: FloatArray,
    val reducedAggregate: FloatArray,
    val persistence: FloatArray,
    val persistentBass: FloatArray?,
)

/**
 * Removes candidates that fail a required musical condition, before decoding chooses among them.
 *
 * Scoring alone cannot say no: a dense template always matches a muddy frame a little, and the
 * best of several bad matches still wins. This gate asks the structural questions directly — is
 * the root actually sounding, is the tone that defines the quality there, does the shell carry the
 * identity, does a named seventh persist — and a candidate that fails any of them is removed from
 * consideration rather than merely scored down.
 *
 * Evidence comes from the bass-reduced view, so a moving bass note cannot establish a root, and
 * from per-frame persistence, so a melody note passing through a span cannot either. Each check
 * tolerates one neighboring span, because a real chord briefly masked (a bar where the root lives
 * only in the bass, a beat where the third decays under the drums) is still the same chord.
 */
internal object ChordCandidateGate {

    @Suppress("LongParameterList")
    fun eliminateUnsupportedCandidates(
        emissions: List<FloatArray>,
        spans: List<Pair<Long, Long>>,
        rawAggregates: List<FloatArray>,
        reducedAggregates: List<FloatArray>,
        frames: Array<FloatArray>,
        persistentBass: List<FloatArray>,
        hopSeconds: Double,
        trace: ChordDecisionTrace?,
    ) {
        val verdicts = spans.indices.map { index ->
            val evidence = SpanEvidence(
                rawAggregate = rawAggregates[index],
                reducedAggregate = reducedAggregates[index],
                persistence = persistenceOf(framesBetween(frames, spans[index], hopSeconds), rawAggregates[index]),
                persistentBass = persistentBass.getOrNull(index),
            )
            ChordTemplates.Candidates.map { candidate -> verdict(candidate, evidence) }
        }

        for (index in spans.indices) {
            val eliminated = BooleanArray(ChordTemplates.Candidates.size) { state ->
                eliminatedWithNeighborTolerance(verdicts, index, state)
            }
            // The confidence floor: when no candidate at all survives, the span offers no usable
            // structural evidence, and removing everything would hand the decision to noise. The
            // gate stands down and leaves the decoder's stickiness and the no-chord state to it.
            val applied = eliminated.any { it } && eliminated.any { !it }
            if (applied) {
                for (state in eliminated.indices) {
                    if (eliminated[state]) emissions[index][state] -= EliminationPenalty
                }
            }
            trace?.onSpan(SpanEliminationReport(index, spans[index].first, spans[index].second, applied, verdicts[index]))
        }
    }

    internal fun verdict(candidate: ChordTemplates.Candidate, evidence: SpanEvidence): CandidateVerdict {
        val rawPeak = max(evidence.rawAggregate.maxOrNull() ?: 0f, 1e-6f)
        val reducedPeak = max(evidence.reducedAggregate.maxOrNull() ?: 0f, 1e-6f)

        // A tone's support is the better of the two views. The raw view can be dominated by a loud
        // bass; the bass-reduced view can suppress a legitimate chord tone that shares a pitch
        // class with a bass partial. A tone genuinely sounding stands clear in at least one.
        fun support(pitchClass: Int): Float = max(
            evidence.rawAggregate[pitchClass] / rawPeak,
            evidence.reducedAggregate[pitchClass] / reducedPeak,
        )

        val rootPc = candidate.root
        val stationaryBass = evidence.persistentBass?.getOrNull(rootPc) ?: 0f
        // The root alone does not get the raw view: that is exactly where a loud moving bass note
        // dominates. It must stand in the bass-reduced view, or be the stationary bass itself —
        // persistent-bass support is built from agreement across neighboring spans, which a
        // walking line never achieves, so a passing bass note cannot establish a root.
        val rootSupport = max(evidence.reducedAggregate[rootPc] / reducedPeak, stationaryBass)
        val rootPass = rootSupport >= RootSupportFloor && evidence.persistence[rootPc] >= RootPersistenceFloor

        val definingPc = definingPitchClass(candidate)
        val definingSupport = support(definingPc)
        val definingPass = definingSupport >= DefiningSupportFloor &&
            evidence.persistence[definingPc] >= DefiningPersistenceFloor

        val fifthSupport = support(fifthPitchClass(candidate))
        val shellSupport = shellSupport(max(rootSupport, support(rootPc)), definingSupport, fifthSupport)

        val seventhPc = seventhPitchClass(candidate)
        val seventhPersistence = seventhPc?.let { evidence.persistence[it] } ?: 1f
        val seventhPass = seventhPc == null ||
            (seventhPersistence >= SeventhPersistenceFloor && support(seventhPc) >= SeventhSupportFloor)

        return CandidateVerdict(
            candidateName = candidate.name,
            rootSupport = rootSupport,
            rootPass = rootPass,
            definingSupport = definingSupport,
            definingPass = definingPass,
            shellSupport = shellSupport,
            shellPass = shellSupport >= ShellSupportFloor,
            seventhPersistence = seventhPersistence,
            seventhPass = seventhPass,
        )
    }

    /**
     * The structural shell — root, defining third or suspension, fifth function — carries identity.
     * The fifth is the most commonly omitted shell tone in real playing, so it has the least weight,
     * and a missing fifth alone cannot fail a shell whose root and defining tone are clearly there.
     */
    private fun shellSupport(root: Float, defining: Float, fifth: Float): Float =
        RootShellWeight * root + DefiningShellWeight * defining + FifthShellWeight * fifth

    /**
     * How much of a span each pitch class is audibly present for, not merely averaged into.
     *
     * Presence is measured against the tone's own span-average level, not the frame's loudest
     * tone: whether a tone was sounding throughout is a question about that tone, and a loud bass
     * elsewhere in the frame must not be able to answer it. A steady tone tracks its own average
     * in every frame; a struck melody note towers over its average briefly and then is gone.
     */
    private fun persistenceOf(frames: List<FloatArray>, rawAggregate: FloatArray): FloatArray {
        val persistence = FloatArray(12)
        if (frames.isEmpty()) return persistence
        for (frame in frames) {
            for (pc in 0 until 12) {
                val floor = max(FramePresenceFloor, rawAggregate[pc] * SelfRelativePresenceRatio)
                if (frame[pc] >= floor) persistence[pc]++
            }
        }
        for (pc in 0 until 12) persistence[pc] /= frames.size
        return persistence
    }

    /**
     * A check failed here may borrow a pass from the span on either side. A chord is a region, not
     * an instant; one span where a tone ducks under the mix does not un-play the chord around it.
     */
    private fun eliminatedWithNeighborTolerance(
        verdicts: List<List<CandidateVerdict>>,
        span: Int,
        state: Int,
    ): Boolean {
        val here = verdicts[span][state]
        if (!here.eliminated) return false
        val before = verdicts.getOrNull(span - 1)?.get(state)
        val after = verdicts.getOrNull(span + 1)?.get(state)
        fun passes(check: (CandidateVerdict) -> Boolean): Boolean =
            check(here) || (before?.let(check) == true) || (after?.let(check) == true)
        return !(passes { it.rootPass } && passes { it.definingPass } &&
            passes { it.shellPass } && passes { it.seventhPass })
    }

    private fun definingPitchClass(candidate: ChordTemplates.Candidate): Int =
        Math.floorMod(candidate.root + candidate.template.intervals[1], 12)

    private fun fifthPitchClass(candidate: ChordTemplates.Candidate): Int {
        for ((position, interval) in candidate.template.intervals.withIndex()) {
            if (position < 2) continue
            val normalized = Math.floorMod(interval, 12)
            if (normalized in FifthFunctionIntervals) return Math.floorMod(candidate.root + interval, 12)
        }
        return Math.floorMod(candidate.root + 7, 12)
    }

    private fun seventhPitchClass(candidate: ChordTemplates.Candidate): Int? {
        val interval = when (candidate.template.seventh) {
            SeventhType.MINOR -> 10
            SeventhType.MAJOR -> 11
            SeventhType.DIMINISHED -> 9
            SeventhType.NONE -> return null
        }
        return Math.floorMod(candidate.root + interval, 12)
    }

    private fun framesBetween(
        frames: Array<FloatArray>,
        span: Pair<Long, Long>,
        hopSeconds: Double,
    ): List<FloatArray> {
        if (frames.isEmpty()) return emptyList()
        val first = ((span.first / 1000.0) / hopSeconds).toInt().coerceIn(0, frames.lastIndex)
        val lastExclusive = kotlin.math.ceil((span.second / 1000.0) / hopSeconds).toInt()
            .coerceIn(first + 1, frames.size)
        return frames.sliceArray(first until lastExclusive).asList()
    }

    private val FifthFunctionIntervals = setOf(6, 7, 8)

    /**
     * Large against emission scores near 1.0: an eliminated candidate is out of consideration, not
     * merely disadvantaged. Finite rather than infinite so decoding always has a defined path.
     */
    private const val EliminationPenalty = 4f

    /** Root evidence relative to the span's strongest tone, in the bass-reduced view. */
    const val RootSupportFloor = 0.30f

    /** The root must be audible for most of the span — a struck note, not a passing one. */
    const val RootPersistenceFloor = 0.45f

    const val DefiningSupportFloor = 0.22f
    const val DefiningPersistenceFloor = 0.40f

    /** A named seventh must persist; a transient seventh leaves the parent triad standing instead. */
    const val SeventhSupportFloor = 0.22f
    const val SeventhPersistenceFloor = 0.40f

    const val ShellSupportFloor = 0.30f

    private const val RootShellWeight = 0.40f
    private const val DefiningShellWeight = 0.35f
    private const val FifthShellWeight = 0.25f

    /** A tone counts as present in a frame only when it holds near its own span-average level. */
    private const val FramePresenceFloor = 0.08f
    private const val SelfRelativePresenceRatio = 0.5f
}
