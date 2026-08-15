package com.alekpeed.hearsay.tools.desktop.capture

import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType

/**
 * What to play, in what order, to build a labeled corpus.
 *
 * The app asks for one chord at a time and checks the notes against what it asked for, so a
 * recording is labeled by construction rather than by somebody naming a file afterward. A corpus
 * whose labels are trusted rather than verified is worse than no corpus: the errors are invisible
 * and they teach the model the wrong thing.
 *
 * The blocks are ordered by how much they matter, not by how they sound. Inversions and rootless
 * voicings come early because the bass note is what decides a chord's identity and it is where the
 * analyzer is weakest.
 */
enum class Block(val title: String, val purpose: String) {
    CORE("Core qualities", "Every quality in root position, so the vocabulary is covered at all."),
    INVERSIONS("Inversions", "The same chord over each of its own tones. The bass decides the name."),
    REGISTER("Register", "One quality with the bass in three octaves, to expose register bias."),
    VOICINGS("Voicings", "Shell, open, two-hand and rootless shapes of the same harmony."),
    EXTENSIONS("Extensions", "The colors, on the chord families that actually take them."),
    AMBIGUOUS("Ambiguous pairs", "Identical notes, different chord. Only the bass separates them."),
    PEDAL("Pedal", "Chords blurred into each other, which is how a piano is really played."),
    MELODY("Melody on top", "A non-chord tone held above the chord, sounding as clearly as it does."),
}

/** The shape of the hand, which changes what reaches the analyzer far more than the symbol does. */
enum class Voicing(val label: String, val instruction: String) {
    CLOSE("Close", "Right hand only, all notes inside one octave."),
    SHELL("Shell", "Root, third and seventh only — no fifth."),
    OPEN("Open", "Root, fifth, then the third an octave higher."),
    TWO_HAND_CLOSE("Two hands, close", "Left hand plays the bass note, right hand sits just above it."),
    TWO_HAND_WIDE("Two hands, wide", "Left hand plays the bass low, right hand an octave or more above."),
    ROOTLESS("Rootless", "Left hand plays the root alone. Right hand plays the chord without it."),
}

/**
 * One prompt: a chord, a shape, a bass note and a register.
 *
 * [label] is the ground truth — what the chord is, regardless of how it is voiced. [extraIntervals]
 * are tones deliberately sounded that the label does not contain, which is how a held melody note
 * is recorded honestly: the note is really there, and the chord is still the chord.
 */
data class CaptureItem(
    val id: String,
    val block: Block,
    val label: Chord,
    val voicing: Voicing,
    val inversion: Int = 0,
    val lowestOctave: Int = 3,
    val extraIntervals: Set<Int> = emptySet(),
    val instruction: String? = null,
) {

    /** The pitch classes that must sound, given the voicing thins some of them out. */
    fun expectedPitchClasses(): Set<Int> {
        val tones = structuralTones(label)
        val base = when (voicing) {
            Voicing.SHELL -> setOfNotNull(tones.getOrNull(0), tones.getOrNull(1), tones.getOrNull(3))
            Voicing.OPEN -> setOfNotNull(tones.getOrNull(0), tones.getOrNull(2), tones.getOrNull(1))
            else -> null
        }
        val intervals = base ?: return label.pitchClasses() + extraIntervals.map { pitchClassOf(it) }
        return (intervals + extraIntervals).map { pitchClassOf(it) }.toSet()
    }

    /** Which pitch class has to be the lowest note sounding. */
    fun requiredBassPitchClass(): Int {
        val tones = structuralTones(label)
        val interval = tones.getOrNull(inversion) ?: tones.first()
        return pitchClassOf(interval)
    }

    private fun pitchClassOf(interval: Int) = Math.floorMod(label.root.pitchClass + interval, 12)

    /** What the screen says, in the words a musician would use. */
    fun prompt(): String = ChordFormatter.format(label)

    fun detail(): String = buildList {
        add(voicing.label)
        if (inversion > 0) add(inversionName(inversion))
        add("bass around ${NoteSpelling.fromPitchClass(requiredBassPitchClass())}$lowestOctave")
        instruction?.let { add(it) }
    }.joinToString(" · ")

    private fun inversionName(index: Int) = when (index) {
        1 -> "1st inversion"
        2 -> "2nd inversion"
        3 -> "3rd inversion"
        else -> "root position"
    }
}

/**
 * Root, third, fifth, seventh as semitones above the root, in that order.
 *
 * Mirrors the private interval logic on [Chord] rather than expanding a symbol, because inversions
 * are defined by which structural tone is underneath — a color tone in the bass is a different
 * chord, not an inversion of this one.
 */
internal fun structuralTones(chord: Chord): List<Int> = buildList {
    add(0)
    when (chord.quality) {
        ChordQuality.MAJOR, ChordQuality.AUGMENTED -> add(4)
        ChordQuality.MINOR, ChordQuality.DIMINISHED -> add(3)
        ChordQuality.SUSPENDED -> add(if (2 in chord.suspensions) 2 else 5)
        ChordQuality.POWER -> Unit
    }
    when {
        Alteration.FLAT_FIVE in chord.alterations -> add(6)
        Alteration.SHARP_FIVE in chord.alterations -> add(8)
        chord.quality == ChordQuality.DIMINISHED -> add(6)
        chord.quality == ChordQuality.AUGMENTED -> add(8)
        else -> add(7)
    }
    when (chord.seventh) {
        SeventhType.MINOR -> add(10)
        SeventhType.MAJOR -> add(11)
        SeventhType.DIMINISHED -> add(9)
        SeventhType.NONE -> Unit
    }
}

object Curriculum {

    private val Roots = (0..11).map { NoteSpelling.fromPitchClass(it, preferFlats = it in setOf(1, 3, 6, 8, 10)) }

    private fun chord(root: NoteSpelling, build: Chord.() -> Chord) = Chord(root = root).build()

    /** The fourteen identities the app can name, before any color is added. */
    private val CoreQualities: List<Pair<String, Chord.() -> Chord>> = listOf(
        "maj" to { this },
        "min" to { copy(quality = ChordQuality.MINOR) },
        "7" to { copy(seventh = SeventhType.MINOR) },
        "maj7" to { copy(seventh = SeventhType.MAJOR) },
        "m7" to { copy(quality = ChordQuality.MINOR, seventh = SeventhType.MINOR) },
        "dim" to { copy(quality = ChordQuality.DIMINISHED) },
        "m7b5" to { copy(quality = ChordQuality.DIMINISHED, seventh = SeventhType.MINOR) },
        "dim7" to { copy(quality = ChordQuality.DIMINISHED, seventh = SeventhType.DIMINISHED) },
        "aug" to { copy(quality = ChordQuality.AUGMENTED) },
        "sus4" to { copy(quality = ChordQuality.SUSPENDED, suspensions = setOf(4)) },
        "sus2" to { copy(quality = ChordQuality.SUSPENDED, suspensions = setOf(2)) },
        "6" to { copy(sixth = true) },
        "m6" to { copy(quality = ChordQuality.MINOR, sixth = true) },
        "5" to { copy(quality = ChordQuality.POWER) },
    )

    /** The four seventh chords worth inverting: everything in the repertoire is built from them. */
    private val SeventhQualities: List<Pair<String, Chord.() -> Chord>> = listOf(
        "7" to { copy(seventh = SeventhType.MINOR) },
        "maj7" to { copy(seventh = SeventhType.MAJOR) },
        "m7" to { copy(quality = ChordQuality.MINOR, seventh = SeventhType.MINOR) },
        "m7b5" to { copy(quality = ChordQuality.DIMINISHED, seventh = SeventhType.MINOR) },
    )

    /** Tensions, each on a family that voices it. The enricher refuses the rest by vocabulary. */
    private val Colors: List<Pair<String, Chord.() -> Chord>> = listOf(
        "9" to { copy(seventh = SeventhType.MINOR, extensions = setOf(9)) },
        "7b9" to { copy(seventh = SeventhType.MINOR, alterations = setOf(Alteration.FLAT_NINE)) },
        "7#9" to { copy(seventh = SeventhType.MINOR, alterations = setOf(Alteration.SHARP_NINE)) },
        "13" to { copy(seventh = SeventhType.MINOR, extensions = setOf(9, 13)) },
        "7b13" to { copy(seventh = SeventhType.MINOR, alterations = setOf(Alteration.FLAT_THIRTEEN)) },
        "7#11" to { copy(seventh = SeventhType.MINOR, alterations = setOf(Alteration.SHARP_ELEVEN)) },
        "7sus4" to {
            copy(quality = ChordQuality.SUSPENDED, suspensions = setOf(4), seventh = SeventhType.MINOR)
        },
        "m9" to { copy(quality = ChordQuality.MINOR, seventh = SeventhType.MINOR, extensions = setOf(9)) },
        "m11" to {
            copy(quality = ChordQuality.MINOR, seventh = SeventhType.MINOR, extensions = setOf(9, 11))
        },
        "maj9" to { copy(seventh = SeventhType.MAJOR, extensions = setOf(9)) },
        "maj7#11" to { copy(seventh = SeventhType.MAJOR, alterations = setOf(Alteration.SHARP_ELEVEN)) },
        "6/9" to { copy(sixth = true, extensions = setOf(9)) },
    )

    /**
     * Chords built from the same notes, separated only by which one is underneath.
     *
     * Recorded back to back so the corpus contains the discrimination explicitly. A model that
     * never sees the two side by side has no reason to learn that the bass decides, and the last
     * two pairs are the extreme case: a diminished seventh and an augmented triad are symmetrical,
     * so nothing above the bass can name them at all.
     */
    private class AmbiguousPair(val first: Chord.() -> Chord, val second: Chord.() -> Chord)

    private val AmbiguousPairs = listOf(
        // Cm7 against Eb6: the same four notes, root a minor third apart.
        AmbiguousPair(
            first = { copy(quality = ChordQuality.MINOR, seventh = SeventhType.MINOR) },
            second = { transposedBy(3).copy(sixth = true) },
        ),
        // Cm7b5 against Ebm6.
        AmbiguousPair(
            first = { copy(quality = ChordQuality.DIMINISHED, seventh = SeventhType.MINOR) },
            second = { transposedBy(3).copy(quality = ChordQuality.MINOR, sixth = true) },
        ),
        // Cdim7 against Ebdim7: one symmetrical stack, four possible names.
        AmbiguousPair(
            first = { copy(quality = ChordQuality.DIMINISHED, seventh = SeventhType.DIMINISHED) },
            second = {
                transposedBy(3).copy(
                    quality = ChordQuality.DIMINISHED,
                    seventh = SeventhType.DIMINISHED,
                )
            },
        ),
        // Caug against Eaug: three possible names, no internal evidence for any of them.
        AmbiguousPair(
            first = { copy(quality = ChordQuality.AUGMENTED) },
            second = { transposedBy(4).copy(quality = ChordQuality.AUGMENTED) },
        ),
        // Csus2 against Gsus4: C D G either way round.
        AmbiguousPair(
            first = { copy(quality = ChordQuality.SUSPENDED, suspensions = setOf(2)) },
            second = { transposedBy(7).copy(quality = ChordQuality.SUSPENDED, suspensions = setOf(4)) },
        ),
    )

    private val AmbiguousKeys = listOf(0, 3, 6, 9)

    fun all(): List<CaptureItem> = core() + inversions() + register() + voicings() +
        extensions() + ambiguous() + pedal() + melody()

    private fun core(): List<CaptureItem> = Roots.flatMap { root ->
        CoreQualities.map { (name, build) ->
            CaptureItem(
                id = "core-$root-$name",
                block = Block.CORE,
                label = chord(root, build).normalized(),
                voicing = Voicing.CLOSE,
                lowestOctave = 3,
            )
        }
    }

    private fun inversions(): List<CaptureItem> = Roots.flatMap { root ->
        SeventhQualities.flatMap { (name, build) ->
            (0..3).map { inversion ->
                CaptureItem(
                    id = "inv-$root-$name-$inversion",
                    block = Block.INVERSIONS,
                    label = chord(root, build).normalized(),
                    voicing = Voicing.CLOSE,
                    inversion = inversion,
                    lowestOctave = 3,
                )
            }
        }
    }

    private fun register(): List<CaptureItem> = Roots.flatMap { root ->
        listOf(1, 2, 3).map { octave ->
            CaptureItem(
                id = "reg-$root-$octave",
                block = Block.REGISTER,
                label = chord(root) { copy(quality = ChordQuality.MINOR, seventh = SeventhType.MINOR) },
                voicing = Voicing.TWO_HAND_WIDE,
                lowestOctave = octave,
                instruction = "Same chord above, bass note in octave $octave",
            )
        }
    }

    private fun voicings(): List<CaptureItem> {
        val shapes = listOf(
            Voicing.SHELL, Voicing.OPEN, Voicing.TWO_HAND_CLOSE, Voicing.TWO_HAND_WIDE, Voicing.ROOTLESS,
        )
        return Roots.flatMap { root ->
            SeventhQualities.take(3).flatMap { (name, build) ->
                shapes.map { shape ->
                    CaptureItem(
                        id = "voi-$root-$name-${shape.name}",
                        block = Block.VOICINGS,
                        label = chord(root, build).normalized(),
                        voicing = shape,
                        lowestOctave = if (shape == Voicing.TWO_HAND_WIDE) 2 else 3,
                    )
                }
            }
        }
    }

    private fun extensions(): List<CaptureItem> = Roots.flatMap { root ->
        Colors.map { (name, build) ->
            CaptureItem(
                id = "ext-$root-$name",
                block = Block.EXTENSIONS,
                label = chord(root, build).normalized(),
                voicing = Voicing.TWO_HAND_CLOSE,
                lowestOctave = 3,
            )
        }
    }

    private fun ambiguous(): List<CaptureItem> = AmbiguousKeys.flatMap { key ->
        AmbiguousPairs.flatMapIndexed { index, pair ->
            val root = Roots[key]
            listOf(
                CaptureItem(
                    id = "amb-$key-$index-a",
                    block = Block.AMBIGUOUS,
                    label = chord(root, pair.first).normalized(),
                    voicing = Voicing.TWO_HAND_CLOSE,
                    lowestOctave = 2,
                ),
                CaptureItem(
                    id = "amb-$key-$index-b",
                    block = Block.AMBIGUOUS,
                    label = chord(root, pair.second).normalized(),
                    voicing = Voicing.TWO_HAND_CLOSE,
                    lowestOctave = 2,
                    instruction = "Same notes as the last one — the bass is the difference",
                ),
            )
        }
    }

    private fun pedal(): List<CaptureItem> = Roots.flatMap { root ->
        SeventhQualities.map { (name, build) ->
            CaptureItem(
                id = "ped-$root-$name",
                block = Block.PEDAL,
                label = chord(root, build).normalized(),
                voicing = Voicing.TWO_HAND_CLOSE,
                lowestOctave = 2,
                instruction = "Sustain pedal down, let the previous chord bleed into this one",
            )
        }
    }

    /**
     * A held note the chord does not contain.
     *
     * This is the case that produced the worst symbols the app has printed: a melody note genuinely
     * persists, so every evidence threshold passes, and the recognizer folds it into the chord. The
     * label here says the note is not part of the harmony, which is the only way to teach that.
     */
    private fun melody(): List<CaptureItem> = Roots.flatMap { root ->
        listOf(1, 6, 8).map { interval ->
            CaptureItem(
                id = "mel-$root-$interval",
                block = Block.MELODY,
                label = chord(root) { copy(quality = ChordQuality.MINOR, seventh = SeventhType.MINOR) },
                voicing = Voicing.TWO_HAND_CLOSE,
                lowestOctave = 2,
                extraIntervals = setOf(interval),
                instruction = "Hold ${NoteSpelling.fromPitchClass(
                    Math.floorMod(root.pitchClass + interval, 12),
                )} on top as a melody note — it is not part of the chord",
            )
        }
    }
}
