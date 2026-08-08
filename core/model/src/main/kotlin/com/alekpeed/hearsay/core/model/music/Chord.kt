package com.alekpeed.hearsay.core.model.music

import kotlinx.serialization.Serializable

/**
 * Triad quality. Seventh, extensions and alterations are modelled separately so that
 * `dominant` and `half-diminished` are derived facts rather than a second way of spelling
 * the same chord.
 */
enum class ChordQuality { MAJOR, MINOR, DIMINISHED, AUGMENTED, SUSPENDED, POWER }

enum class SeventhType { NONE, MINOR, MAJOR, DIMINISHED }

enum class Alteration(val degree: Int, val semitonesFromRoot: Int) {
    FLAT_FIVE(5, 6),
    SHARP_FIVE(5, 8),
    FLAT_NINE(9, 1),
    SHARP_NINE(9, 3),
    SHARP_ELEVEN(11, 6),
    FLAT_THIRTEEN(13, 8),
}

/**
 * A structured chord. The display string is always derived, never the source of truth — a
 * correction, a transposition or a change of notation must not lose what the analysis meant.
 */
@Serializable
data class Chord(
    val root: NoteSpelling,
    val quality: ChordQuality = ChordQuality.MAJOR,
    val seventh: SeventhType = SeventhType.NONE,
    val sixth: Boolean = false,
    val extensions: Set<Int> = emptySet(),
    val alterations: Set<Alteration> = emptySet(),
    val suspensions: Set<Int> = emptySet(),
    val additions: Set<Int> = emptySet(),
    val omissions: Set<Int> = emptySet(),
    val bass: NoteSpelling? = null,
) {

    /** A major triad with a minor seventh, however it was spelled on the way in. */
    val isDominant: Boolean
        get() = seventh == SeventhType.MINOR &&
            (quality == ChordQuality.MAJOR || quality == ChordQuality.SUSPENDED)

    val isHalfDiminished: Boolean
        get() = quality == ChordQuality.DIMINISHED && seventh == SeventhType.MINOR

    val isSlash: Boolean get() = bass != null && bass.pitchClass != root.pitchClass

    /** The note actually sounding at the bottom, which is the root unless a slash bass overrides it. */
    val effectiveBass: NoteSpelling get() = bass ?: root

    /**
     * The pitch classes this symbol literally names. Implied-but-unwritten tones (the ninth inside
     * a written thirteenth, for instance) are deliberately excluded — voicing evidence comes from
     * transcribed notes, not from expanding a symbol.
     */
    fun pitchClasses(): Set<Int> {
        val result = linkedSetOf(root.pitchClass)
        thirdIntervals().forEach { result += offset(it) }
        fifthInterval()?.let { result += offset(it) }
        seventhInterval()?.let { result += offset(it) }
        colorIntervals().forEach { result += offset(it) }
        bass?.let { result += it.pitchClass }
        return result
    }

    /** A list rather than a single value because a chord can be written `sus2sus4`. */
    private fun thirdIntervals(): List<Int> {
        if (3 in omissions) return emptyList()
        return when (quality) {
            ChordQuality.MAJOR, ChordQuality.AUGMENTED -> listOf(4)
            ChordQuality.MINOR, ChordQuality.DIMINISHED -> listOf(3)
            ChordQuality.SUSPENDED -> suspensions.sorted().map { if (it == 2) 2 else 5 }
            ChordQuality.POWER -> emptyList()
        }
    }

    private fun fifthInterval(): Int? = when {
        5 in omissions -> null
        Alteration.FLAT_FIVE in alterations -> 6
        Alteration.SHARP_FIVE in alterations -> 8
        quality == ChordQuality.DIMINISHED -> 6
        quality == ChordQuality.AUGMENTED -> 8
        else -> 7
    }

    private fun seventhInterval(): Int? = when (seventh) {
        SeventhType.MINOR -> 10
        SeventhType.MAJOR -> 11
        SeventhType.DIMINISHED -> 9
        SeventhType.NONE -> null
    }

    /** Sixths, extensions, additions and the alterations above the fifth. */
    private fun colorIntervals(): List<Int> = buildList {
        if (sixth) add(9)
        (extensions + additions).forEach { degree -> intervalOfDegree(degree)?.let { add(it) } }
        alterations.filter { it.degree > 5 }.forEach { add(it.semitonesFromRoot) }
    }

    private fun intervalOfDegree(degree: Int): Int? = when (degree) {
        6, 13 -> 9
        9 -> 2
        11 -> 5
        else -> null
    }

    fun transposedBy(semitones: Int, preferFlats: Boolean = root.alteration < 0): Chord = copy(
        root = root.transposedBy(semitones, preferFlats),
        bass = bass?.transposedBy(semitones, preferFlats),
    )

    /**
     * Folds equivalent spellings onto one representation, so that `Cm7b5` and `Cø7` compare equal
     * and a correction written either way round-trips to the same stored chord.
     */
    fun normalized(): Chord {
        var result = this
        if (result.isMinorFlatFiveSpelling()) {
            result = result.copy(
                quality = ChordQuality.DIMINISHED,
                alterations = result.alterations - Alteration.FLAT_FIVE,
            )
        }
        if (result.isAugmentedTriadSpelling()) {
            result = result.copy(
                quality = ChordQuality.AUGMENTED,
                alterations = result.alterations - Alteration.SHARP_FIVE,
            )
        }
        if (result.bass != null && result.bass.pitchClass == result.root.pitchClass) {
            result = result.copy(bass = null)
        }
        return result
    }

    /** `Cm♭5` is a diminished triad written the long way round. */
    private fun isMinorFlatFiveSpelling(): Boolean =
        quality == ChordQuality.MINOR && Alteration.FLAT_FIVE in alterations

    /**
     * `C♯5` with nothing above it is an augmented triad. With a seventh or an extension it is not —
     * `C7♯5` is an altered dominant, and folding it into `Caug7` would lose that.
     */
    private fun isAugmentedTriadSpelling(): Boolean {
        if (quality != ChordQuality.MAJOR || Alteration.SHARP_FIVE !in alterations) return false
        return seventh == SeventhType.NONE && extensions.isEmpty()
    }

    private fun offset(semitones: Int): Int = Math.floorMod(root.pitchClass + semitones, 12)

    companion object {
        fun major(root: String): Chord = Chord(requireNotNull(NoteSpelling.parse(root)))
    }
}
