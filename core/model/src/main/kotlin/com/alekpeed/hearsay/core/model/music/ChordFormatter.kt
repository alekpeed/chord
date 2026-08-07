package com.alekpeed.hearsay.core.model.music

/**
 * How chord symbols are drawn. The same stored [Chord] renders as `Cmaj7`, `CΔ7` or `CM7`
 * depending on what the player is used to reading; none of these is the stored truth.
 */
data class SymbolStyle(
    val unicodeAccidentals: Boolean = true,
    val minorSymbol: String = "m",
    val majorSeventhSymbol: String = "maj",
    val halfDiminishedAsSlashFive: Boolean = true,
    val diminishedSymbol: String = "dim",
    val augmentedSymbol: String = "aug",
) {
    companion object {
        val Default = SymbolStyle()
        val Ascii = SymbolStyle(unicodeAccidentals = false)
        val Jazz = SymbolStyle(
            minorSymbol = "–",
            majorSeventhSymbol = "Δ",
            halfDiminishedAsSlashFive = false,
            diminishedSymbol = "°",
            augmentedSymbol = "+",
        )
    }
}

/** Renders a structured [Chord] as the symbol a musician reads off the chart. */
object ChordFormatter {

    fun format(chord: Chord, style: SymbolStyle = SymbolStyle.Default): String {
        val normalized = chord.normalized()
        val builder = StringBuilder(normalized.root.render(style.unicodeAccidentals))
        builder.append(core(normalized, style))
        builder.append(suspensionSuffix(normalized))
        builder.append(alterationSuffix(normalized, style))
        builder.append(additionSuffix(normalized))
        builder.append(omissionSuffix(normalized))
        normalized.bass?.let { builder.append('/').append(it.render(style.unicodeAccidentals)) }
        return builder.toString()
    }

    /** The part between the root and any slash bass: quality, seventh and the highest extension. */
    private fun core(chord: Chord, style: SymbolStyle): String = when {
        chord.quality == ChordQuality.POWER -> "5"
        chord.isHalfDiminished -> halfDiminishedCore(chord, style)
        chord.quality == ChordQuality.DIMINISHED -> diminishedCore(chord, style)
        chord.seventh == SeventhType.NONE && chord.extensions.isEmpty() -> triadCore(chord, style)
        else -> seventhCore(chord, style)
    }

    private fun halfDiminishedCore(chord: Chord, style: SymbolStyle): String {
        val degree = chord.extensions.maxOrNull() ?: 7
        return if (style.halfDiminishedAsSlashFive) {
            style.minorSymbol + degree + flat(style) + "5"
        } else {
            "ø$degree"
        }
    }

    private fun diminishedCore(chord: Chord, style: SymbolStyle): String = when (chord.seventh) {
        SeventhType.DIMINISHED, SeventhType.MINOR -> style.diminishedSymbol + "7"
        SeventhType.MAJOR -> style.diminishedSymbol + "(" + style.majorSeventhSymbol + "7)"
        SeventhType.NONE -> style.diminishedSymbol
    }

    private fun triadCore(chord: Chord, style: SymbolStyle): String =
        qualityPrefix(chord, style) + sixthPart(chord).orEmpty()

    private fun seventhCore(chord: Chord, style: SymbolStyle): String {
        // A sixth is not written alongside a seventh: the seventh chord's own stack already names
        // that degree, since a written thirteenth is the same pitch as the sixth.
        val degree = chord.extensions.maxOrNull() ?: 7

        if (chord.quality == ChordQuality.MINOR && chord.seventh == SeventhType.MAJOR) {
            return style.minorSymbol + "(" + style.majorSeventhSymbol + degree + ")"
        }

        val body = when (chord.seventh) {
            SeventhType.MAJOR -> style.majorSeventhSymbol + degree
            SeventhType.MINOR -> degree.toString()
            SeventhType.DIMINISHED -> style.diminishedSymbol + degree
            SeventhType.NONE -> "add$degree"
        }
        return qualityPrefix(chord, style) + body
    }

    private fun sixthPart(chord: Chord): String? = when {
        chord.sixth && 9 in chord.additions -> "6/9"
        chord.sixth -> "6"
        else -> null
    }

    private fun qualityPrefix(chord: Chord, style: SymbolStyle): String = when (chord.quality) {
        ChordQuality.MINOR -> style.minorSymbol
        ChordQuality.AUGMENTED -> style.augmentedSymbol
        else -> ""
    }

    private fun suspensionSuffix(chord: Chord): String =
        chord.suspensions.sorted().joinToString("") { "sus$it" }

    private fun alterationSuffix(chord: Chord, style: SymbolStyle): String {
        val rendered = chord.alterations
            .sortedBy { it.degree }
            .map { alteration ->
                val sign = when (alteration) {
                    Alteration.FLAT_FIVE, Alteration.FLAT_NINE, Alteration.FLAT_THIRTEEN -> flat(style)
                    Alteration.SHARP_FIVE, Alteration.SHARP_NINE, Alteration.SHARP_ELEVEN -> sharp(style)
                }
                sign + alteration.degree
            }
        return when {
            rendered.isEmpty() -> ""
            rendered.size == 1 -> rendered.single()
            else -> rendered.joinToString(",", prefix = "(", postfix = ")")
        }
    }

    private fun additionSuffix(chord: Chord): String {
        val additions = chord.additions.filterNot { it == 9 && chord.sixth }
        return additions.sorted().joinToString("") { "add$it" }
    }

    private fun omissionSuffix(chord: Chord): String =
        chord.omissions.sorted().joinToString("") { "(no$it)" }

    private fun flat(style: SymbolStyle) = if (style.unicodeAccidentals) "♭" else "b"
    private fun sharp(style: SymbolStyle) = if (style.unicodeAccidentals) "♯" else "#"
}
