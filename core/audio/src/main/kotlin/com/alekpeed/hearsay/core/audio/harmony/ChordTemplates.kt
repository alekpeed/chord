package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType

/**
 * The chord vocabulary the recognizer can actually name, as pitch-class templates.
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
    /** Weighted pitch-class vector: the notes that define the quality carry the most weight. */
    fun vector(root: Int): FloatArray {
        val out = FloatArray(12)
        for ((position, interval) in intervals.withIndex()) {
            val weight = when (position) {
                0 -> RootWeight
                1 -> DefiningWeight
                else -> UpperWeight
            }
            out[Math.floorMod(root + interval, 12)] += weight
        }
        var sum = 0.0
        for (value in out) sum += value.toDouble() * value
        val norm = kotlin.math.sqrt(sum).toFloat()
        if (norm > 1e-6f) for (i in out.indices) out[i] /= norm
        return out
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
        const val RootWeight = 1.15f
        const val DefiningWeight = 1.1f
        const val UpperWeight = 0.85f
    }
}

object ChordTemplates {

    /**
     * Interval lists are ordered root, then the tone that defines the quality (the third, or the
     * fourth in a suspension), then everything else — which is what the weighting above reads.
     *
     * Sevenths carry a modest structural prior over sixth chords because a sixth chord and the
     * relative minor seventh can contain exactly the same pitch classes. In that exact tie the
     * richer seventh identity is the safer default; sustained bass and temporal context can still
     * establish a genuine sixth chord when the recording supports it.
     */
    val All: List<ChordTemplate> = listOf(
        ChordTemplate("", listOf(0, 4, 7), ChordQuality.MAJOR, SeventhType.NONE, prior = 1.08f),
        ChordTemplate("m", listOf(0, 3, 7), ChordQuality.MINOR, SeventhType.NONE, prior = 1.06f),
        ChordTemplate("7", listOf(0, 4, 10, 7), ChordQuality.MAJOR, SeventhType.MINOR, prior = 1.12f),
        ChordTemplate("m7", listOf(0, 3, 10, 7), ChordQuality.MINOR, SeventhType.MINOR, prior = 1.16f),
        ChordTemplate("maj7", listOf(0, 4, 11, 7), ChordQuality.MAJOR, SeventhType.MAJOR, prior = 1.14f),
        ChordTemplate("6", listOf(0, 4, 9, 7), ChordQuality.MAJOR, SeventhType.NONE, sixth = true, prior = 0.84f),
        ChordTemplate("m6", listOf(0, 3, 9, 7), ChordQuality.MINOR, SeventhType.NONE, sixth = true, prior = 0.82f),
        ChordTemplate(
            "sus4", listOf(0, 5, 7), ChordQuality.SUSPENDED, SeventhType.NONE,
            suspensions = setOf(4), prior = 0.9f),
        ChordTemplate(
            "7sus4", listOf(0, 5, 10, 7), ChordQuality.SUSPENDED, SeventhType.MINOR,
            suspensions = setOf(4), prior = 0.86f),
        ChordTemplate(
            "sus2", listOf(0, 2, 7), ChordQuality.SUSPENDED, SeventhType.NONE,
            suspensions = setOf(2), prior = 0.8f),
        ChordTemplate("dim", listOf(0, 3, 6), ChordQuality.DIMINISHED, SeventhType.NONE, prior = 0.86f),
        ChordTemplate("dim7", listOf(0, 3, 6, 9), ChordQuality.DIMINISHED, SeventhType.DIMINISHED, prior = 0.84f),
        ChordTemplate("m7b5", listOf(0, 3, 6, 10), ChordQuality.DIMINISHED, SeventhType.MINOR, prior = 0.9f),
        ChordTemplate("aug", listOf(0, 4, 8), ChordQuality.AUGMENTED, SeventhType.NONE, prior = 0.76f),
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
        ChordTemplate(
            "mMaj7", listOf(0, 3, 11, 7), ChordQuality.MINOR, SeventhType.MAJOR, prior = 0.66f),
    )

    /**
     * Upper color is deliberately excluded from the Viterbi state space.
     *
     * A 9th, 13th, b9, or #11 can make the same structural harmony match more closely, but it must
     * never be allowed to change the root decision. Letting F13 compete directly with Dm7, for
     * example, makes the shared F-A-C-D pitch set look like a new F-root chord even when the audio
     * is plainly Dm7. The recognizer now decodes root/quality/seventh/sixth/suspension first and
     * adds sustained upper color to that stable identity afterward.
     */
    private val IdentityTemplates: List<ChordTemplate> = All.filter { template ->
        template.extensions.isEmpty() && template.alterations.none { it.degree > 5 }
    }

    /** Every structural identity template at every root, precomputed once. */
    data class Candidate(
        val root: Int,
        val template: ChordTemplate,
        val vector: FloatArray,
    ) {
        val name: String get() = "${PitchNames[root]}${template.label}"

        /**
         * True when [pitchClass] is the sixth or seventh this chord is named for.
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

    /** Sixths and sevenths: intervals above the fifth that name a structural chord beyond its triad. */
    private val ExtensionIntervals = setOf(9, 10, 11)

    /** Index of the no-chord state, appended after every real candidate. */
    val NoChordIndex: Int = Candidates.size

    val StateCount: Int = Candidates.size + 1
}
