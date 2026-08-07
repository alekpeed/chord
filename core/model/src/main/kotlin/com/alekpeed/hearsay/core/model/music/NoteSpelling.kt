package com.alekpeed.hearsay.core.model.music

import kotlinx.serialization.Serializable

/** The seven natural letter names and the pitch class each one sits on. */
enum class Letter(val naturalPitchClass: Int) {
    C(0), D(2), E(4), F(5), G(7), A(9), B(11), ;

    companion object {
        fun fromChar(char: Char): Letter? = entries.firstOrNull { it.name[0] == char.uppercaseChar() }
    }
}

/**
 * A spelled note name: a letter plus a number of semitones of alteration.
 *
 * Spelling is kept separate from pitch class on purpose. `F#` and `Gb` sound identical but mean
 * different things in a chord symbol, and the display layer must be able to say which one the
 * analysis meant.
 */
@Serializable
data class NoteSpelling(val letter: Letter, val alteration: Int = 0) {

    val pitchClass: Int get() = Math.floorMod(letter.naturalPitchClass + alteration, 12)

    fun transposedBy(semitones: Int, preferFlats: Boolean = this.alteration < 0): NoteSpelling =
        fromPitchClass(Math.floorMod(pitchClass + semitones, 12), preferFlats)

    fun render(unicodeAccidentals: Boolean = true): String {
        val symbol = when {
            alteration > 0 -> if (unicodeAccidentals) "♯" else "#"
            alteration < 0 -> if (unicodeAccidentals) "♭" else "b"
            else -> ""
        }
        return letter.name + symbol.repeat(kotlin.math.abs(alteration))
    }

    override fun toString(): String = render(unicodeAccidentals = false)

    companion object {
        private val SharpNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val FlatNames = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

        fun fromPitchClass(pitchClass: Int, preferFlats: Boolean = false): NoteSpelling {
            val normalized = Math.floorMod(pitchClass, 12)
            val name = if (preferFlats) FlatNames[normalized] else SharpNames[normalized]
            return parse(name) ?: NoteSpelling(Letter.C)
        }

        /** Parses a bare note name such as `C`, `F#`, `Bb`, `G##`. Returns null if the text is not one. */
        fun parse(text: String): NoteSpelling? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val letter = Letter.fromChar(trimmed[0]) ?: return null
            var alteration = 0
            for (char in trimmed.drop(1)) {
                when (char) {
                    '#', '♯' -> alteration++
                    'b', '♭' -> alteration--
                    '♮' -> Unit
                    else -> return null
                }
            }
            return NoteSpelling(letter, alteration)
        }
    }
}

/** Mode of a key centre. Only the two the chord vocabulary needs are modelled for now. */
enum class Mode { MAJOR, MINOR }

@Serializable
data class Key(val tonic: NoteSpelling, val mode: Mode = Mode.MAJOR) {
    fun render(unicodeAccidentals: Boolean = true): String =
        tonic.render(unicodeAccidentals) + if (mode == Mode.MINOR) " minor" else " major"

    /** True when this key is conventionally written with flats, used to pick enharmonic spellings. */
    val prefersFlats: Boolean get() = tonic.alteration < 0 || tonic.letter == Letter.F

    fun transposedBy(semitones: Int): Key = copy(tonic = tonic.transposedBy(semitones, prefersFlats))
}
