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

    fun enrich(
        path: IntArray,
        spans: List<Pair<Long, Long>>,
        colorFrames: Array<FloatArray>,
        hopSeconds: Double,
        preferFlats: Boolean,
        enabled: Boolean,
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
                val colored = if (enabled) addStableColor(base, frames) else base
                for (index in start until end) out[index] = colored
            }
            start = end
        }
        return out
    }

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

    private fun addStableColor(base: Chord, frames: List<FloatArray>): Chord {
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
        return if (base.seventh != SeventhType.NONE) addSeventhColor(base, evidence) else addTriadColor(base, evidence)
    }

    private fun addSeventhColor(base: Chord, evidence: Evidence): Chord {
        val extensions = base.extensions.toMutableSet()
        val alterations = base.alterations.filter { it.degree <= 5 }.toMutableSet()

        fun choose(options: List<Option>): Any? = options.filter { supported(base, evidence, it) }
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

    private fun ninthOptions(base: Chord): List<Option> = buildList {
        add(Option(1, Alteration.FLAT_NINE, AlteredMargin, AlteredPersistence))
        add(Option(2, 9, NinthMargin, NinthPersistence))
        if (base.quality == ChordQuality.MAJOR || base.quality == ChordQuality.SUSPENDED) {
            add(Option(3, Alteration.SHARP_NINE, AlteredMargin, AlteredPersistence))
        }
    }

    private fun eleventhOptions(base: Chord): List<Option> = buildList {
        if (base.quality != ChordQuality.SUSPENDED || 4 !in base.suspensions) {
            add(Option(5, 11, EleventhMargin, EleventhPersistence))
        }
        if (base.quality != ChordQuality.DIMINISHED) {
            add(Option(6, Alteration.SHARP_ELEVEN, AlteredEleventhMargin, AlteredPersistence))
        }
    }

    private fun thirteenthOptions(base: Chord): List<Option> = buildList {
        if (base.quality != ChordQuality.AUGMENTED) {
            add(Option(8, Alteration.FLAT_THIRTEEN, AlteredThirteenthMargin, DensePersistence))
        }
        add(Option(9, 13, ThirteenthMargin, ThirteenthPersistence))
    }

    private fun supported(base: Chord, evidence: Evidence, option: Option): Boolean {
        val pitchClass = Math.floorMod(base.root.pitchClass + option.interval, 12)
        val threshold = max(AbsoluteFloor, evidence.structuralLevel * option.relativeMargin)
        return evidence.aggregate[pitchClass] >= threshold &&
            evidence.persistence[pitchClass] >= option.persistenceFloor
    }

    private fun support(base: Chord, evidence: Evidence, interval: Int): Float =
        evidence.aggregate[Math.floorMod(base.root.pitchClass + interval, 12)]

    private fun addTriadColor(base: Chord, evidence: Evidence): Chord {
        if (base.quality != ChordQuality.MAJOR && base.quality != ChordQuality.MINOR) return base
        val additions = base.additions.toMutableSet()
        if (supported(base, evidence, Option(2, 9, TriadNinthMargin, NinthPersistence))) additions += 9
        if (supported(base, evidence, Option(5, 11, TriadEleventhMargin, EleventhPersistence))) additions += 11
        return base.copy(additions = additions).normalized()
    }

    private const val AbsoluteFloor = 0.20f
    private const val FrameAbsoluteFloor = 0.10f
    private const val FrameRelativeFloor = 0.45f
    private const val MinimumFrames = 2
    private const val NinthMargin = 0.52f
    private const val NinthPersistence = 0.55f
    private const val TriadNinthMargin = 0.58f
    private const val EleventhMargin = 0.58f
    private const val EleventhPersistence = 0.60f
    private const val TriadEleventhMargin = 0.64f
    private const val ThirteenthMargin = 0.62f
    private const val ThirteenthPersistence = 0.65f
    private const val AlteredMargin = 0.60f
    private const val AlteredEleventhMargin = 0.62f
    private const val AlteredThirteenthMargin = 0.68f
    private const val AlteredPersistence = 0.65f
    private const val DensePersistence = 0.70f
}
