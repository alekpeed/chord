package com.alekpeed.hearsay.core.model.music

/** Where a note sits in a key: a scale degree plus how far it is bent away from that degree. */
data class ScaleDegree(val index: Int, val accidental: Int) {
    val number: Int get() = index + 1

    fun prefix(unicodeAccidentals: Boolean): String = when {
        accidental > 0 -> if (unicodeAccidentals) "♯" else "#"
        accidental < 0 -> if (unicodeAccidentals) "♭" else "b"
        else -> ""
    }

    companion object {
        private val MajorOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val MinorOffsets = intArrayOf(0, 2, 3, 5, 7, 8, 10)

        fun of(note: NoteSpelling, key: Key): ScaleDegree {
            val offsets = if (key.mode == Mode.MINOR) MinorOffsets else MajorOffsets
            val interval = Math.floorMod(note.pitchClass - key.tonic.pitchClass, 12)

            offsets.indexOfFirst { it == interval }.takeIf { it >= 0 }?.let { return ScaleDegree(it, 0) }

            val raised = offsets.indexOfFirst { Math.floorMod(it + 1, 12) == interval }
            val lowered = offsets.indexOfFirst { Math.floorMod(it - 1, 12) == interval }

            // Respect the spelling the analysis chose: F♯ in C is ♯4, G♭ in C is ♭5.
            val preferRaised = note.alteration > 0
            return when {
                preferRaised && raised >= 0 -> ScaleDegree(raised, 1)
                lowered >= 0 -> ScaleDegree(lowered, -1)
                raised >= 0 -> ScaleDegree(raised, 1)
                else -> ScaleDegree(0, 0)
            }
        }
    }
}

/**
 * Renders a chord as a Roman numeral relative to a key.
 *
 * Slash basses are written as a degree rather than figured bass. This app is aimed at players
 * reading changes, for whom `♭VII/2` says more at a glance than `6/5` does.
 */
object RomanNumeralFormatter {

    private val Numerals = listOf("I", "II", "III", "IV", "V", "VI", "VII")

    fun format(chord: Chord, key: Key, style: SymbolStyle = SymbolStyle.Default): String {
        val normalized = chord.normalized()
        val degree = ScaleDegree.of(normalized.root, key)
        val minorish = normalized.quality == ChordQuality.MINOR || normalized.quality == ChordQuality.DIMINISHED
        val numeral = Numerals[degree.index].let { if (minorish) it.lowercase() else it }

        val builder = StringBuilder(degree.prefix(style.unicodeAccidentals))
        builder.append(numeral)
        builder.append(suffixOf(normalized, style))
        normalized.bass?.let {
            val bassDegree = ScaleDegree.of(it, key)
            builder.append('/').append(bassDegree.prefix(style.unicodeAccidentals)).append(bassDegree.number)
        }
        return builder.toString()
    }

    /** The part of the symbol after the root, taken from the one place that knows how to draw it. */
    internal fun suffixOf(chord: Chord, style: SymbolStyle): String {
        val withoutBass = chord.copy(bass = null)
        val rendered = ChordFormatter.format(withoutBass, style)
        return rendered.removePrefix(withoutBass.root.render(style.unicodeAccidentals))
    }
}

/** Renders a chord in the Nashville number system, relative to a key. */
object NashvilleFormatter {

    fun format(chord: Chord, key: Key, style: SymbolStyle = SymbolStyle.Default): String {
        val normalized = chord.normalized()
        val degree = ScaleDegree.of(normalized.root, key)

        val builder = StringBuilder(degree.prefix(style.unicodeAccidentals))
        builder.append(degree.number)
        builder.append(RomanNumeralFormatter.suffixOf(normalized, style))
        normalized.bass?.let {
            val bassDegree = ScaleDegree.of(it, key)
            builder.append('/').append(bassDegree.prefix(style.unicodeAccidentals)).append(bassDegree.number)
        }
        return builder.toString()
    }
}
