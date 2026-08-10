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
    /** Always true. Retained so a trace records that the span was judged rather than skipped. */
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
 * from per-frame persistence, so a melody note passing through a span cannot either. Every check
 * is answered within its own span: an earlier version let a candidate borrow a passing verdict
 * from a neighbor, which put a chord nobody played one stray frame away from being licensed.
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
            // Every candidate answers for its own span. Borrowing a pass from a neighbor was one
            // stray frame away from validating a chord nobody played: a single B-flat anywhere
            // near a G and a D would license G minor across three spans, which is exactly the
            // invention this gate exists to stop.
            for (state in verdicts[index].indices) {
                if (verdicts[index][state].eliminated) emissions[index][state] -= EliminationPenalty
            }
            // No standing down. A span where nothing validates is a span with no legible harmony,
            // and the honest output is silence: the no-chord state is left unpenalized, so it wins
            // and the row stays blank. Disabling the filter there instead handed the muddiest
            // moments in the recording to whichever template happened to fit them best.
            trace?.onSpan(SpanEliminationReport(index, spans[index].first, spans[index].second, true, verdicts[index]))
        }
    }

    internal fun verdict(candidate: ChordTemplates.Candidate, evidence: SpanEvidence): CandidateVerdict {
        val reducedPeak = max(evidence.reducedAggregate.maxOrNull() ?: 0f, 1e-6f)

        // One view, not the better of two: asking twice and accepting either answer gave every
        // tone two chances at a bar it should clear once. The view is the bass-reduced one,
        // because these are the tones stacked above the bass and they must be measured against
        // each other — against the raw chroma, a bass note several times louder than the harmony
        // makes every voice over it look absent.
        //
        // The one exception is a tone the bass is itself holding. The reduction deliberately
        // erases that pitch class, but a note in the bass is the least disputable note in the
        // recording, so an inversion is credited rather than denied its own bass.
        fun support(pitchClass: Int): Float = max(
            evidence.reducedAggregate[pitchClass] / reducedPeak,
            evidence.persistentBass?.getOrNull(pitchClass) ?: 0f,
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

    /**
     * The tone that makes a chord major rather than minor, or suspended rather than either, has to
     * be genuinely audible — not merely above the noise. At the old 0.22 a B-flat bin filled by
     * nothing but the seventh harmonic of a C, or bin leakage from the B natural next door, was
     * enough to license G minor in a recording containing no B-flat at all.
     */
    const val DefiningSupportFloor = 0.40f
    const val DefiningPersistenceFloor = 0.60f

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
