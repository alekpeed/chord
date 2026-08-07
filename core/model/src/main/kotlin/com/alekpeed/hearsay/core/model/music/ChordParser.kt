package com.alekpeed.hearsay.core.model.music

/**
 * Reads a chord symbol typed by a user or produced by a model and turns it into structured data.
 *
 * The parser is deliberately forgiving about notation — `C-7`, `Cmin7` and `Cm7` all mean the same
 * chord, and a musician correcting a row should not have to learn this app's preferred spelling.
 * It is not forgiving about nonsense: anything it cannot account for returns null rather than a
 * confident guess.
 */
object ChordParser {

    private val NoChordSymbols = setOf("N.C.", "NC", "N/C", "—", "-", "")

    fun isNoChord(text: String): Boolean = text.trim().uppercase() in NoChordSymbols

    fun parse(text: String): Chord? {
        val cleaned = text.trim().replace("–", "-").replace("—", "-")
        if (cleaned.isEmpty() || isNoChord(cleaned)) return null

        val (symbolPart, bass) = splitBass(cleaned) ?: return null
        val rootLength = rootLength(symbolPart) ?: return null
        val root = NoteSpelling.parse(symbolPart.take(rootLength)) ?: return null

        val builder = Builder(root = root, bass = bass)
        var index = rootLength
        val body = symbolPart

        while (index < body.length) {
            val consumed = builder.consume(body, index, isFirstToken = index == rootLength)
            if (consumed == 0) return null
            index += consumed
        }
        return builder.build()
    }

    /** Splits a trailing slash bass, taking care not to eat the slash in `C6/9`. */
    private fun splitBass(text: String): Pair<String, NoteSpelling?>? {
        val slash = text.lastIndexOf('/')
        if (slash <= 0 || slash == text.lastIndex) return text to null
        val candidate = text.substring(slash + 1)
        val bass = NoteSpelling.parse(candidate) ?: return text to null
        return text.take(slash) to bass
    }

    private fun rootLength(text: String): Int? {
        if (text.isEmpty() || Letter.fromChar(text[0]) == null) return null
        var length = 1
        // An accidental directly after the letter always belongs to the root: `Bb9` is B flat,
        // not B with a flat ninth. A flat ninth is written after a degree, as in `C7b9`.
        while (length < text.length && text[length] in "#b♯♭") length++
        return length
    }

    private class Builder(val root: NoteSpelling, val bass: NoteSpelling?) {
        var quality = ChordQuality.MAJOR
        var seventh = SeventhType.NONE
        var sixth = false
        var majorSeventhMarked = false
        var fullyDiminishedMarked = false
        val extensions = mutableSetOf<Int>()
        val alterations = mutableSetOf<Alteration>()
        val suspensions = mutableSetOf<Int>()
        val additions = mutableSetOf<Int>()
        val omissions = mutableSetOf<Int>()

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun consume(text: String, at: Int, isFirstToken: Boolean): Int {
            val rest = text.substring(at)

            ignorable(rest)?.let { return it }

            // Order matters: `maj` must be tested before `m`, `sus4` before `sus`.
            majorSeventhToken(rest)?.let { majorSeventhMarked = true; return it }
            minorToken(rest, isFirstToken)?.let { quality = ChordQuality.MINOR; return it }
            halfDiminishedToken(rest)?.let {
                quality = ChordQuality.DIMINISHED
                seventh = SeventhType.MINOR
                return it
            }
            diminishedToken(rest)?.let {
                quality = ChordQuality.DIMINISHED
                fullyDiminishedMarked = true
                return it
            }
            augmentedToken(rest, isFirstToken)?.let { quality = ChordQuality.AUGMENTED; return it }
            suspensionToken(rest)?.let { return it }
            additionToken(rest)?.let { return it }
            omissionToken(rest)?.let { return it }
            alteredDominantToken(rest)?.let { return it }
            alterationToken(rest)?.let { return it }
            degreeToken(rest)?.let { return it }
            return 0
        }

        private fun ignorable(rest: String): Int? = if (rest[0] in " (),") 1 else null

        private fun majorSeventhToken(rest: String): Int? = when {
            rest.startsWith("major") -> 5
            rest.startsWith("maj") -> 3
            rest.startsWith("Maj") -> 3
            rest.startsWith("ma") && !rest.startsWith("mai") -> 2
            rest.startsWith("Δ") -> 1
            rest.startsWith("^") -> 1
            rest.startsWith("M") && !rest.startsWith("Ma") -> 1
            else -> null
        }

        // A leading dash means minor (`C-7`); anywhere else it is a flat sign (`C7-5`).
        private fun minorToken(rest: String, isFirstToken: Boolean): Int? = when {
            rest.startsWith("min") -> 3
            rest.startsWith("m") -> 1
            rest.startsWith("-") && isFirstToken -> 1
            else -> null
        }

        private fun halfDiminishedToken(rest: String): Int? = if (rest.startsWith("ø")) 1 else null

        private fun diminishedToken(rest: String): Int? = when {
            rest.startsWith("dim") -> 3
            rest.startsWith("°") -> 1
            else -> null
        }

        private fun augmentedToken(rest: String, isFirstToken: Boolean): Int? = when {
            rest.startsWith("aug") -> 3
            rest.startsWith("+") && isFirstToken && !(rest.length > 1 && rest[1].isDigit()) -> 1
            else -> null
        }

        private fun suspensionToken(rest: String): Int? = when {
            rest.startsWith("sus2") -> { quality = ChordQuality.SUSPENDED; suspensions += 2; 4 }
            rest.startsWith("sus4") -> { quality = ChordQuality.SUSPENDED; suspensions += 4; 4 }
            rest.startsWith("sus") -> { quality = ChordQuality.SUSPENDED; suspensions += 4; 3 }
            else -> null
        }

        private fun additionToken(rest: String): Int? {
            if (!rest.startsWith("add")) return null
            val digits = rest.drop(3).takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            additions += digits.toInt()
            return 3 + digits.length
        }

        private fun omissionToken(rest: String): Int? {
            val prefix = listOf("omit", "no").firstOrNull { rest.startsWith(it) } ?: return null
            val digits = rest.drop(prefix.length).takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            omissions += digits.toInt()
            return prefix.length + digits.length
        }

        /** `alt` is shorthand for an altered dominant: flat ninth, sharp ninth, sharp eleven, flat thirteen. */
        private fun alteredDominantToken(rest: String): Int? {
            if (!rest.startsWith("alt")) return null
            if (seventh == SeventhType.NONE) seventh = SeventhType.MINOR
            alterations += listOf(
                Alteration.FLAT_NINE,
                Alteration.SHARP_NINE,
                Alteration.SHARP_ELEVEN,
                Alteration.FLAT_THIRTEEN,
            )
            return 3
        }

        private fun alterationToken(rest: String): Int? {
            val sign = when (rest[0]) {
                'b', '♭', '-' -> -1
                '#', '♯', '+' -> 1
                else -> return null
            }
            val digits = rest.drop(1).takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            val alteration = when (digits.toInt() to sign) {
                5 to -1 -> Alteration.FLAT_FIVE
                5 to 1 -> Alteration.SHARP_FIVE
                9 to -1 -> Alteration.FLAT_NINE
                9 to 1 -> Alteration.SHARP_NINE
                11 to 1 -> Alteration.SHARP_ELEVEN
                13 to -1 -> Alteration.FLAT_THIRTEEN
                else -> return null
            }
            alterations += alteration
            // A written alteration above the seventh implies the seventh is there.
            if (alteration.degree > 7 && seventh == SeventhType.NONE) seventh = SeventhType.MINOR
            return 1 + digits.length
        }

        private fun degreeToken(rest: String): Int? {
            if (rest.startsWith("6/9") || rest.startsWith("69")) {
                sixth = true
                additions += 9
                return if (rest.startsWith("6/9")) 3 else 2
            }
            val digits = rest.takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            when (digits.toInt()) {
                5 -> if (quality == ChordQuality.MAJOR && seventh == SeventhType.NONE) {
                    quality = ChordQuality.POWER
                }
                6 -> sixth = true
                7 -> seventh = seventhForWrittenDegree()
                9, 11, 13 -> {
                    extensions += digits.toInt()
                    if (seventh == SeventhType.NONE) seventh = seventhForWrittenDegree()
                }
                else -> return null
            }
            return digits.length
        }

        private fun seventhForWrittenDegree(): SeventhType = when {
            majorSeventhMarked -> SeventhType.MAJOR
            fullyDiminishedMarked -> SeventhType.DIMINISHED
            else -> SeventhType.MINOR
        }

        fun build(): Chord = Chord(
            root = root,
            quality = quality,
            seventh = seventh,
            sixth = sixth,
            extensions = extensions,
            alterations = alterations,
            suspensions = suspensions,
            additions = additions,
            omissions = omissions,
            bass = bass,
        ).normalized()
    }
}
