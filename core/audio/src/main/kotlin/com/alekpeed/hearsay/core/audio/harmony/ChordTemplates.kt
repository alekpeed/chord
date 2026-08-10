package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType

/**
 * The chord vocabulary the recognizer can actually name, as pitch-class templates.
 *
 * Three shapes were removed after a real recording came back littered with them. Dsus2 is D-E-A,
 * which is equally A-D-E — an Asus4 — so no evidence can separate the two spellings and the
 * recognizer was free to reach a fourth away from the harmony either side of it for a name that
 * fit identically. Augmented and minor-major-seventh are genuinely rare in this repertoire and
 * were earning their keep only by matching muddy frames: a template that is almost never right
 * costs more in false positives than it returns in the few bars it would have named correctly.
 * They can come back when there is evidence they are being missed rather than invented.
 *
 * Deliberately wider than the triads-only vocabulary most chord apps use — sevenths, sixths and
 * ninths are the point for the repertoire this product targets — but not so wide that every
 * ambiguous chroma finds some exotic template to match. Extensions beyond the ninth are left to
 * the user to add, because at that density the evidence rarely justifies the claim.
 */
data class ChordTemplate(
    val label: String,
    val intervals: List<Int>,
    val quality: ChordQuality,
    val seventh: SeventhType,
    val sixth: Boolean = false,
    val extensions: Set<Int> = emptySet(),
    val alterations: Set<Alteration> = emptySet(),
    val suspensions: Set<Int> = emptySet(),
    /** Rarer shapes need stronger evidence, or they win by accident on a muddy frame. */
    val prior: Float = 1f,
) {
    /**
     * Weighted pitch-class vector with structural chord tones given priority over upper color.
     *
     * Root and defining third/suspension carry the most identity weight. The fifth completes the
     * triadic shell, while the named seventh is also strongly structural because it regularly
     * distinguishes the actual chord quality (7, maj7, m7, m7b5, dim7). Ninths, elevenths,
     * thirteenths, and altered upper tensions remain weaker so they cannot steal the root.
     */
    fun vector(root: Int): FloatArray {
        val out = FloatArray(12)
        for ((position, interval) in intervals.withIndex()) {
            val normalized = Math.floorMod(interval, 12)
            val weight = when {
                normalized == 0 -> RootWeight
                position == 1 -> DefiningWeight
                normalized == 7 || normalized == 6 || normalized == 8 -> FifthWeight
                isNamedSeventhInterval(normalized) -> SeventhWeight
                else -> UpperColorWeight
            }
            out[Math.floorMod(root + interval, 12)] += weight
        }
        var sum = 0.0
        for (value in out) sum += value.toDouble() * value
        val norm = kotlin.math.sqrt(sum).toFloat()
        if (norm > 1e-6f) for (i in out.indices) out[i] /= norm
        return out
    }

    private fun isNamedSeventhInterval(interval: Int): Boolean = when (seventh) {
        SeventhType.MINOR -> interval == 10
        SeventhType.MAJOR -> interval == 11
        SeventhType.DIMINISHED -> interval == 9
        SeventhType.NONE -> false
    }

    fun toChord(root: NoteSpelling, bass: NoteSpelling? = null): Chord = Chord(
        root = root,
        quality = quality,
        seventh = seventh,
        sixth = sixth,
        extensions = extensions,
        alterations = alterations,
        suspensions = suspensions,
        bass = bass,
    ).normalized()

    private companion object {
        /** Root evidence must dominate competing subset-root interpretations. */
        const val RootWeight = 1.75f

        /** The third or suspension establishes quality together with the root. */
        const val DefiningWeight = 1.40f

        /** The fifth completes the structural triadic shell. */
        const val FifthWeight = 1.20f

        /** The named seventh is a major structural identifier, not weak upper color. */
        const val SeventhWeight = 1.00f

        /** Ninths, elevenths, thirteenths, and other upper color have the least root authority. */
        const val UpperColorWeight = 0.55f
    }
}

object ChordTemplates {

    /**
     * Interval lists are ordered root, then the tone that defines the quality (the third, or the
     * fourth in a suspension), then everything else. Weighting is structural rather than positional:
     * the fifth remains part of the triadic shell even when a seventh appears before it in the list.
     */
    val All: List<ChordTemplate> = listOf(
        ChordTemplate("", listOf(0, 4, 7), ChordQuality.MAJOR, SeventhType.NONE, prior = 1.1f),
        ChordTemplate("m", listOf(0, 3, 7), ChordQuality.MINOR, SeventhType.NONE, prior = 1.08f),
        ChordTemplate("7", listOf(0, 4, 10, 7), ChordQuality.MAJOR, SeventhType.MINOR, prior = 1.06f),
        ChordTemplate("m7", listOf(0, 3, 10, 7), ChordQuality.MINOR, SeventhType.MINOR, prior = 1.06f),
        ChordTemplate("maj7", listOf(0, 4, 11, 7), ChordQuality.MAJOR, SeventhType.MAJOR, prior = 1.05f),
        ChordTemplate("6", listOf(0, 4, 9, 7), ChordQuality.MAJOR, SeventhType.NONE, sixth = true, prior = 0.95f),
        ChordTemplate("m6", listOf(0, 3, 9, 7), ChordQuality.MINOR, SeventhType.NONE, sixth = true, prior = 0.9f),
        ChordTemplate(
            "sus4", listOf(0, 5, 7), ChordQuality.SUSPENDED, SeventhType.NONE,
            suspensions = setOf(4), prior = 0.9f),
        ChordTemplate(
            "7sus4", listOf(0, 5, 10, 7), ChordQuality.SUSPENDED, SeventhType.MINOR,
            suspensions = setOf(4), prior = 0.86f),
        ChordTemplate("dim", listOf(0, 3, 6), ChordQuality.DIMINISHED, SeventhType.NONE, prior = 0.86f),
        ChordTemplate("dim7", listOf(0, 3, 6, 9), ChordQuality.DIMINISHED, SeventhType.DIMINISHED, prior = 0.84f),
        ChordTemplate("m7b5", listOf(0, 3, 6, 10), ChordQuality.DIMINISHED, SeventhType.MINOR, prior = 0.9f),
        ChordTemplate(
            "9", listOf(0, 4, 10, 7, 2), ChordQuality.MAJOR, SeventhType.MINOR,
            extensions = setOf(9), prior = 0.92f),
        ChordTemplate(
            "m9", listOf(0, 3, 10, 7, 2), ChordQuality.MINOR, SeventhType.MINOR,
            extensions = setOf(9), prior = 0.9f),
        ChordTemplate(
            "maj9", listOf(0, 4, 11, 7, 2), ChordQuality.MAJOR, SeventhType.MAJOR,
            extensions = setOf(9), prior = 0.88f),
        ChordTemplate(
            "13", listOf(0, 4, 10, 9, 7), ChordQuality.MAJOR, SeventhType.MINOR,
            extensions = setOf(13), prior = 0.78f),
        ChordTemplate(
            "7b9", listOf(0, 4, 10, 1, 7), ChordQuality.MAJOR, SeventhType.MINOR,
            alterations = setOf(Alteration.FLAT_NINE), prior = 0.72f),
        ChordTemplate(
            "7#11", listOf(0, 4, 10, 6, 7), ChordQuality.MAJOR, SeventhType.MINOR,
            alterations = setOf(Alteration.SHARP_ELEVEN), prior = 0.68f),
    )

    /**
     * Color is deliberately excluded from the Viterbi state space.
     *
     * A 9th, 13th, b9, #11, or sixth can make the same structural harmony match more closely, but
     * it must never be allowed to choose a different root by itself. F6 and Dm7, for example, are
     * the exact same pitch-class set. Letting both compete as separate root states makes the result
     * depend on voicing rather than harmonic evidence. Root/quality/seventh/suspension are decoded
     * first; sustained color is attached to that stable identity afterward.
     */
    private val IdentityTemplates: List<ChordTemplate> = All.filter { template ->
        !template.sixth && template.extensions.isEmpty() && template.alterations.none { it.degree > 5 }
    }

    /** Every structural identity template at every root, precomputed once. */
    data class Candidate(
        val root: Int,
        val template: ChordTemplate,
        val vector: FloatArray,
    ) {
        val name: String get() = "${PitchNames[root]}${template.label}"

        /**
         * True when [pitchClass] is the seventh this chord is named for.
         *
         * These are the notes that separate a chord from its own triad, and the ones a passing tone
         * in the bass or an overtone can most easily counterfeit. Everything at or below the fifth
         * is structure, not color.
         */
        fun isExtensionTone(pitchClass: Int): Boolean = template.intervals.any { interval ->
            interval in ExtensionIntervals && Math.floorMod(root + interval, 12) == pitchClass
        }

        override fun equals(other: Any?): Boolean =
            other is Candidate && other.root == root && other.template == template

        override fun hashCode(): Int = root * 31 + template.hashCode()
    }

    val PitchNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    val Candidates: List<Candidate> = buildList {
        for (root in 0 until 12) {
            for (template in IdentityTemplates) {
                add(Candidate(root, template, template.vector(root)))
            }
        }
    }

    /** Sevenths: intervals above the fifth that name a structural chord beyond its triad. */
    private val ExtensionIntervals = setOf(9, 10, 11)

    /** Index of the no-chord state, appended after every real candidate. */
    val NoChordIndex: Int = Candidates.size

    val StateCount: Int = Candidates.size + 1
}
