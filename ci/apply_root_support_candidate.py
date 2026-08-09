from pathlib import Path


PATH = Path("core/audio/src/main/kotlin/com/alekpeed/hearsay/core/audio/harmony/ChordRecognizer.kt")


def replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise RuntimeError(f"Expected source fragment not found:\n{old}")
    return text.replace(old, new, 1)


def main() -> None:
    text = PATH.read_text()
    if "private fun persistentBassSupport(" in text:
        return

    text = replace_once(
        text,
        """        val persistentRoots = persistentBassRoots(bassObservations)\n\n        // Bass masking is deliberately limited to change gating. The raw harmonic chroma remains\n""",
        """        val persistentRoots = persistentBassRoots(bassObservations)\n        val persistentSupport = persistentBassSupport(bassObservations)\n\n        // Bass masking is deliberately limited to change gating. The raw harmonic chroma remains\n""",
    )

    text = replace_once(
        text,
        """        val emissions = rawObservations.mapIndexed { index, observation ->\n            emissionScores(observation, priors, persistentRoots.getOrNull(index))\n        }\n        val path = viterbi(emissions, gateChangeLikelihood(changeLikelihood, changeObservations))\n        val refined = refineSpans(spans, path, chroma)\n""",
        """        val emissions = rawObservations.mapIndexed { index, observation ->\n            emissionScores(observation, priors, persistentSupport.getOrNull(index))\n        }\n        val path = viterbi(emissions, gateChangeLikelihood(changeLikelihood, changeObservations))\n        val refined = refineSpans(spans, path, chroma, changeLikelihood)\n""",
    )

    marker = """    /**\n     * Novelty can be excited by a bass note even when bass is not allowed to determine the label.\n"""
    addition = """    /**
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

"""
    text = replace_once(text, marker, addition + marker)

    start = text.index("    private fun emissionScores(\n")
    end = text.index("    private fun hasSeventhSupport", start)
    replacement = """    private fun emissionScores(
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
            scores[index] = dot * priors[index]
        }
        applyPersistentBassRootTieBreak(scores, observed, persistentBassSupport)

        var energy = 0f
        for (value in observed) energy += value * value
        scores[ChordTemplates.NoChordIndex] = if (energy < 1e-4f) 1f else noChordThreshold
        return scores
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

"""
    text = text[:start] + replacement + text[end:]

    text = replace_once(
        text,
        """    private fun refineSpans(\n        spans: List<Pair<Long, Long>>,\n        path: IntArray,\n        chroma: Chromagram,\n    ): List<Pair<Long, Long>> {\n""",
        """    private fun refineSpans(\n        spans: List<Pair<Long, Long>>,\n        path: IntArray,\n        chroma: Chromagram,\n        detectedChangeLikelihood: FloatArray?,\n    ): List<Pair<Long, Long>> {\n""",
    )

    text = replace_once(
        text,
        """        for (index in 1 until spans.size) {\n            if (harmonicIdentity(path[index]) == harmonicIdentity(path[index - 1])) continue\n            starts[index] = bestSplitMs(\n""",
        """        for (index in 1 until spans.size) {\n            if (harmonicIdentity(path[index]) == harmonicIdentity(path[index - 1])) continue\n            // A novelty peak is already an audio-measured boundary. Refining it again against\n            // uncertain chord templates can drag a correct transition hundreds of milliseconds.\n            // Beat-only boundaries still use spectral refinement so anticipated changes remain free\n            // to land ahead of or behind the grid.\n            if ((detectedChangeLikelihood?.getOrNull(index) ?: 0f) > 0f) continue\n            starts[index] = bestSplitMs(\n""",
    )

    text = replace_once(
        text,
        """        const val SustainedSeventhRootBoost = 1.32f\n        const val SeventhSupportRatio = 0.35f\n""",
        """        const val BassTieBreakFloor = 0.86f\n        const val BassRootSupportFloor = 0.35f\n        const val BassRootPreference = 1.08f\n        const val BassPersistenceFloor = 0.25f\n        const val SeventhSupportRatio = 0.35f\n""",
    )

    PATH.write_text(text if text.endswith("\n") else text + "\n")


if __name__ == "__main__":
    main()
