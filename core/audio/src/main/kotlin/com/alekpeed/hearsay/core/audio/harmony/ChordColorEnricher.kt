package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType
import kotlin.math.max

/** Adds only persistent upper color after the structural Viterbi state is settled. */
internal object ChordColorEnricher {

    @Suppress("LongParameterList")
    fun enrich(
        path: IntArray,
        spans: List<Pair<Long, Long>>,
        colorFrames: Array<FloatArray>,
        hopSeconds: Double,
        preferFlats: Boolean,
        enabled: Boolean,
        trace: ChordDecisionTrace? = null,
    ): List<Chord?> {
        val out = MutableList<Chord?>(path.size) { null }
        var start = 0
        while (start < path.size) {
            val state = path[start]
            var end = start + 1
            while (end < path.size && path[end] == state) end++

            if (state != ChordTemplates.NoChordIndex) {
                val candidate = ChordTemplates.Candidates[state]
                val root = NoteSpelling.fromPitchClass(candidate.root, preferFlats)
                val base = candidate.template.toChord(root).copy(
                    extensions = emptySet(),
                    alterations = candidate.template.alterations.filter { it.degree <= 5 }.toSet(),
                    additions = emptySet(),
                    bass = null,
                ).normalized()
                val frames = framesBetween(colorFrames, spans[start].first, spans[end - 1].second, hopSeconds)
                val run = Run(spans[start].first, spans[end - 1].second, trace)
                val colored = if (enabled) addStableColor(base, frames, run) else base
                for (index in start until end) out[index] = colored
            }
            start = end
        }
        return out
    }

    /** One structural run being colored, with somewhere to explain each decision. */
    private class Run(val startMs: Long, val endMs: Long, val trace: ChordDecisionTrace?)

    private fun framesBetween(
        frames: Array<FloatArray>,
        startMs: Long,
        endMs: Long,
        hopSeconds: Double,
    ): List<FloatArray> {
        if (frames.isEmpty()) return emptyList()
        val first = ((startMs / 1000.0) / hopSeconds).toInt().coerceIn(0, frames.lastIndex)
        val lastExclusive = kotlin.math.ceil((endMs / 1000.0) / hopSeconds).toInt()
            .coerceIn(first + 1, frames.size)
        return frames.sliceArray(first until lastExclusive).asList()
    }

    private data class Evidence(
        val aggregate: FloatArray,
        val persistence: FloatArray,
        val structuralLevel: Float,
    )

    private data class Option(
        val interval: Int,
        val value: Any,
        val relativeMargin: Float,
        val persistenceFloor: Float,
    )

    private fun addStableColor(base: Chord, frames: List<FloatArray>, run: Run): Chord {
        if (frames.size < MinimumFrames) return base
        val structural = base.copy(
            extensions = emptySet(),
            alterations = base.alterations.filter { it.degree <= 5 }.toSet(),
            additions = emptySet(),
            bass = null,
        )
        val structuralPitchClasses = structural.pitchClasses()
        val aggregate = FloatArray(Chromagram.PitchClasses)
        val persistentCounts = IntArray(Chromagram.PitchClasses)
        for (frame in frames) {
            val structuralLevel = structuralPitchClasses.map { frame.getOrElse(it) { 0f } }.average().toFloat()
            val threshold = max(FrameAbsoluteFloor, structuralLevel * FrameRelativeFloor)
            for (pc in aggregate.indices) {
                aggregate[pc] += frame[pc]
                if (frame[pc] >= threshold) persistentCounts[pc]++
            }
        }
        val normalized = Chromagram.normalize(aggregate)
        val structuralLevel = structuralPitchClasses.map { normalized.getOrElse(it) { 0f } }
            .average().toFloat().coerceAtLeast(1e-6f)
        val evidence = Evidence(
            aggregate = normalized,
            persistence = FloatArray(Chromagram.PitchClasses) { persistentCounts[it].toFloat() / frames.size },
            structuralLevel = structuralLevel,
        )
        // A plain triad is left plain.
        //
        // Added ninths and elevenths on a triad were where the melody ended up: a sustained sung or
        // played scale tone over a C major triad became Cadd9 or Cadd11, and the second of those is
        // barely a chord anyone voices — a natural eleventh sits a semitone from the major third it
        // would have to share the symbol with. Both are rare in real charts and neither survived
        // contact with a real recording. A seventh chord still takes color, because there the
        // upper structure is the point of the name.
        return if (base.seventh != SeventhType.NONE) addSeventhColor(base, evidence, run) else base
    }

    private fun addSeventhColor(base: Chord, evidence: Evidence, run: Run): Chord {
        val extensions = base.extensions.toMutableSet()
        val alterations = base.alterations.filter { it.degree <= 5 }.toMutableSet()

        fun choose(options: List<Option>): Any? = options.filter { supported(base, evidence, it, run) }
            .maxByOrNull { option ->
                support(base, evidence, option.interval) - evidence.structuralLevel * option.relativeMargin
            }?.value
        fun add(choice: Any?) {
            when (choice) {
                is Int -> extensions += choice
                is Alteration -> alterations += choice
            }
        }

        add(choose(ninthOptions(base)))
        add(choose(eleventhOptions(base)))
        if (!base.sixth) add(choose(thirteenthOptions(base)))
        return base.copy(extensions = extensions, alterations = alterations).normalized()
    }

    /**
     * Which tensions a chord family actually takes, before any evidence is weighed.
     *
     * The old lists offered nearly every tension on nearly every seventh chord and left the
     * evidence floors to sort it out. Evidence floors cannot: a sustained melody note or a
     * neighboring chord's bleed can be genuinely persistent, so the recognizer emitted names like
     * Am7b9 and Cmaj13b9 — arithmetically defensible, musically absurd, and reported from a real
     * chart. A flat nine a semitone above a minor chord's root is a sound composers avoid so
     * consistently that its appearance on a chart is far more likely to be an analysis error than
     * a performance, and the same is true of a natural eleventh clashing against a major third.
     *
     * So the vocabulary is now functional, encoding what players actually voice:
     *
     *  - Natural ninths sit on any seventh chord outside the diminished family: 9, m9, maj9 are
     *    all ordinary. On m7b5 and dim7 the plain scale tone is the flat nine, and the natural
     *    nine is the exotic locrian-natural-2 color — a produced chart almost never means it, so
     *    diminished-family chords take no ninth at all.
     *  - Altered ninths (b9, #9) belong to dominants, where they are the ordinary altered colors.
     *    The sharp nine needs a major third to alter against, so among the altered colors a sus
     *    dominant takes only b9 (7sus4b9, the phrygian sus) — on a thirdless chord the "#9" pitch
     *    class is simply the minor third, and the honest symbol is m7.
     *  - The natural eleventh belongs to minor chords (m11); over a major third it is the textbook
     *    avoid note, which players resolve by sharpening it or suspending the third.
     *  - The sharp eleventh belongs to dominants with their third (7#11) and major sevenths
     *    (maj7#11, the lydian color) — the two places it is genuinely voiced. On a sus chord it
     *    sits a semitone above the structural suspended fourth, the same clash that bans the
     *    natural eleventh over a major third.
     *  - Thirteenths, natural and flat, belong to dominants. Minor and major thirteenths exist in
     *    theory and books; on a chart produced from a mixed recording they are overwhelmingly
     *    misreadings, and a rare true one costs less to under-name than a stream of false ones
     *    costs to trust.
     */
    private fun ninthOptions(base: Chord): List<Option> = buildList {
        if (base.quality != ChordQuality.DIMINISHED) {
            add(Option(2, 9, NinthMargin, NinthPersistence))
        }
        if (base.isDominant) {
            add(Option(1, Alteration.FLAT_NINE, AlteredMargin, AlteredPersistence))
        }
        // #9 needs a major third to alter against; on a sus dominant interval 3 is the minor third.
        if (base.quality == ChordQuality.MAJOR && base.seventh == SeventhType.MINOR) {
            add(Option(3, Alteration.SHARP_NINE, AlteredMargin, AlteredPersistence))
        }
    }

    private fun eleventhOptions(base: Chord): List<Option> = buildList {
        if (base.quality == ChordQuality.MINOR) {
            add(Option(5, 11, EleventhMargin, EleventhPersistence))
        }
        // #11 needs a major third below it; on a sus chord it clashes with the structural fourth.
        if (base.quality == ChordQuality.MAJOR &&
            (base.seventh == SeventhType.MINOR || base.seventh == SeventhType.MAJOR)
        ) {
            add(Option(6, Alteration.SHARP_ELEVEN, AlteredEleventhMargin, AlteredPersistence))
        }
    }

    private fun thirteenthOptions(base: Chord): List<Option> = buildList {
        if (base.isDominant) {
            add(Option(8, Alteration.FLAT_THIRTEEN, AlteredThirteenthMargin, DensePersistence))
            add(Option(9, 13, ThirteenthMargin, ThirteenthPersistence))
        }
    }

    private fun supported(base: Chord, evidence: Evidence, option: Option, run: Run): Boolean {
        val pitchClass = Math.floorMod(base.root.pitchClass + option.interval, 12)
        val threshold = max(AbsoluteFloor, evidence.structuralLevel * option.relativeMargin)
        val supported = evidence.aggregate[pitchClass] >= threshold &&
            evidence.persistence[pitchClass] >= option.persistenceFloor
        run.trace?.onColor(
            run.startMs,
            run.endMs,
            String.format(
                java.util.Locale.US,
                "color %s: support %.2f (required %.2f), persistence %.2f (required %.2f) -> %s",
                option.value,
                evidence.aggregate[pitchClass],
                threshold,
                evidence.persistence[pitchClass],
                option.persistenceFloor,
                if (supported) "kept for ranking" else "eliminated to parent",
            ),
        )
        return supported
    }

    private fun support(base: Chord, evidence: Evidence, interval: Int): Float =
        evidence.aggregate[Math.floorMod(base.root.pitchClass + interval, 12)]

    private const val AbsoluteFloor = 0.20f
    private const val FrameAbsoluteFloor = 0.10f
    private const val FrameRelativeFloor = 0.45f
    private const val MinimumFrames = 2
    private const val NinthMargin = 0.52f
    private const val NinthPersistence = 0.55f
    private const val EleventhMargin = 0.58f
    private const val EleventhPersistence = 0.60f
    private const val ThirteenthMargin = 0.62f
    private const val ThirteenthPersistence = 0.65f
    private const val AlteredMargin = 0.60f
    private const val AlteredEleventhMargin = 0.62f
    private const val AlteredThirteenthMargin = 0.68f
    private const val AlteredPersistence = 0.65f
    private const val DensePersistence = 0.70f
}
