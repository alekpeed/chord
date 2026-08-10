package com.alekpeed.hearsay.core.audio.harmony

/** Cosine distance between two normalized chroma observations, as harmonic movement in 0..1. */
internal fun observationDistance(before: FloatArray, after: FloatArray): Float {
    var dot = 0f
    for (pc in before.indices) dot += before[pc] * after[pc]
    return (1f - dot).coerceIn(0f, 1f)
}

/**
 * The temporal-confirmation and hysteresis stages: a decoded change is a candidate, not a fact,
 * until it has lived long enough and explained the audio clearly better than what it replaces.
 */
internal object StructuralTransitionGate {

    /**
     * Requires the bass to move before the harmony is allowed to.
     *
     * A new chord that nobody can hear the bottom of is the failure this exists for: the upper
     * templates rearrange themselves over a held bass and the chart sprouts a row the player has
     * no way to hear. Players locate a chord change by its bass. So a change of root now needs the
     * low band to name a different note across the boundary, and a run whose bass never moves is
     * folded back into the harmony it came from.
     *
     * This is the necessary half of the rule, not the sufficient half: bass motion alone still
     * cannot create or rename a chord — a walking line under one harmony is averaged out long
     * before this stage. Both directions hold, and they do not conflict. Bass must move for the
     * harmony to move, and its moving is never on its own a reason to say it did.
     *
     * A span whose bass is too quiet to name counts as no evidence and therefore blocks the
     * change. When there is no low band at all — bass tracking disabled, or a caller supplying
     * none — the rule cannot be evaluated and stands down rather than freezing the whole chart on
     * its opening chord.
     */
    fun requireBassMovement(path: IntArray, bassPitchClasses: List<Int?>?) {
        if (bassPitchClasses == null || path.size < 2) return
        var established = path[0]
        var establishedBass = bassPitchClasses.getOrNull(0)
        var index = 1
        while (index < path.size) {
            if (path[index] == established) {
                // The bass under an unchanged chord is still the bass this chord is sitting on, so
                // a later change is measured against where the line actually is, not where the
                // chord started. Otherwise an inversion mid-chord would permanently unlock changes.
                bassPitchClasses.getOrNull(index)?.let { establishedBass = it }
                index++
                continue
            }

            val candidate = path[index]
            var end = index + 1
            while (end < path.size && path[end] == candidate) end++

            val movedTo = (index until end).firstNotNullOfOrNull { span ->
                bassPitchClasses.getOrNull(span)?.takeIf { it != establishedBass }
            }
            if (movedTo != null) {
                established = candidate
                establishedBass = movedTo
            } else {
                for (span in index until end) path[span] = established
            }
            index = end
        }
    }

    /**
     * Confirms a decoded structural change from duration, non-bass change evidence, and margin.
     *
     * This is a candidate-confirmation pass rather than a blanket minimum-duration filter. A weak
     * candidate needs 650 ms of continuous dominance. Exceptionally clear upper-harmony movement
     * can confirm after 350 ms, preserving two-chords-per-second passages. Until either condition
     * is met, the established identity remains active; a short C7-Am-C7 sandwich therefore never
     * becomes a displayed Am.
     *
     * A root change is additionally a major event with its own hysteresis: the challenger must
     * outscore the established chord by a real margin over its whole run, so a tiny score
     * advantage can never rewrite the root. Same-root refinements — C becoming C7, C7 becoming a
     * suspension — confirm faster and without the margin, because the elimination gate has
     * already required their distinguishing tone to persist in the audio.
     */
    fun confirmStructuralChanges(
        path: IntArray,
        spans: List<Pair<Long, Long>>,
        upperObservations: List<FloatArray>,
        changeLikelihood: FloatArray?,
        emissions: List<FloatArray>,
    ) {
        if (path.size < 2) return
        var established = path[0]
        var index = 1
        while (index < path.size) {
            if (path[index] == established) {
                index++
                continue
            }

            val candidate = path[index]
            var end = index + 1
            while (end < path.size && path[end] == candidate) end++
            if (candidate == ChordTemplates.NoChordIndex || established == ChordTemplates.NoChordIndex) {
                established = candidate
                index = end
                continue
            }

            val durationMs = spans[end - 1].second - spans[index].first
            val sameRoot = ChordTemplates.Candidates[candidate].root == ChordTemplates.Candidates[established].root
            val confirmed = if (sameRoot) {
                durationMs >= SameRootConfirmationMs
            } else {
                val upperDistance = observationDistance(upperObservations[index - 1], upperObservations[index])
                val novelty = changeLikelihood?.getOrNull(index) ?: 0f
                val stronglyCorroborated = upperDistance >= StrongChangeDistance && novelty >= StrongChangeLikelihood
                val longEnough = durationMs >= NormalConfirmationMs ||
                    (stronglyCorroborated && durationMs >= StrongConfirmationMs)
                longEnough && meanAdvantage(emissions, index, end, candidate, established) >= RootChangeMargin
            }

            if (confirmed) {
                established = candidate
            } else {
                for (span in index until end) path[span] = established
            }
            index = end
        }
    }

    /**
     * Collapses a short isolated reading surrounded by one and the same structural chord.
     *
     * Confirmation already blocks weak brief candidates; this handles the ones that squeaked past
     * it between two readings of the same harmony. Unless the middle run explains its own audio
     * decisively better than the surrounding chord does, three rows saying C7, something, C7 were
     * one C7 all along. A genuine passing chord keeps its overwhelming local evidence and stays.
     */
    fun collapseSandwichNoise(path: IntArray, spans: List<Pair<Long, Long>>, emissions: List<FloatArray>) {
        if (path.size < 3) return
        var index = 1
        while (index < path.size) {
            val state = path[index]
            if (state == path[index - 1]) {
                index++
                continue
            }
            var end = index + 1
            while (end < path.size && path[end] == state) end++
            if (end >= path.size) return

            val surrounding = path[index - 1]
            val durationMs = spans[end - 1].second - spans[index].first
            val sandwiched = path[end] == surrounding && state != ChordTemplates.NoChordIndex &&
                surrounding != ChordTemplates.NoChordIndex
            if (sandwiched && durationMs <= SandwichMaxMs &&
                meanAdvantage(emissions, index, end, state, surrounding) < SandwichOverwhelmingMargin
            ) {
                for (span in index until end) path[span] = surrounding
            }
            index = end
        }
    }

    /** How much better the challenger explains the run than the chord it wants to replace. */
    fun meanAdvantage(
        emissions: List<FloatArray>,
        from: Int,
        untilExclusive: Int,
        challenger: Int,
        established: Int,
    ): Float {
        var total = 0f
        for (span in from until untilExclusive) total += emissions[span][challenger] - emissions[span][established]
        return total / (untilExclusive - from)
    }

    const val NormalConfirmationMs = 650L
    const val StrongConfirmationMs = 350L
    const val SameRootConfirmationMs = 350L
    const val StrongChangeDistance = 0.32f
    const val StrongChangeLikelihood = 0.65f
    const val RootChangeMargin = 0.04f
    const val SandwichMaxMs = 600L
    const val SandwichOverwhelmingMargin = 0.12f
}
