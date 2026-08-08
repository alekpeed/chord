package com.alekpeed.hearsay.core.model.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordParserTest {

    private fun parse(symbol: String): Chord =
        requireNotNull(ChordParser.parse(symbol)) { "Expected to parse $symbol" }

    @Test
    fun `parses a bare major triad`() {
        val chord = parse("C")
        assertEquals(NoteSpelling(Letter.C), chord.root)
        assertEquals(ChordQuality.MAJOR, chord.quality)
        assertEquals(SeventhType.NONE, chord.seventh)
    }

    @Test
    fun `an accidental after the letter belongs to the root`() {
        assertEquals(NoteSpelling(Letter.B, -1), parse("Bb9").root)
        assertEquals(NoteSpelling(Letter.F, 1), parse("F#m7").root)
    }

    @Test
    fun `minor is spelled three ways and means one thing`() {
        val expected = Chord(NoteSpelling(Letter.C), ChordQuality.MINOR, SeventhType.MINOR)
        assertEquals(expected, parse("Cm7"))
        assertEquals(expected, parse("Cmin7"))
        assertEquals(expected, parse("C-7"))
    }

    @Test
    fun `major seventh is spelled four ways and means one thing`() {
        val expected = Chord(NoteSpelling(Letter.C), ChordQuality.MAJOR, SeventhType.MAJOR)
        assertEquals(expected, parse("Cmaj7"))
        assertEquals(expected, parse("CM7"))
        assertEquals(expected, parse("CΔ7"))
        assertEquals(expected, parse("C^7"))
    }

    @Test
    fun `a plain seventh is a dominant`() {
        val chord = parse("G7")
        assertEquals(SeventhType.MINOR, chord.seventh)
        assertEquals(ChordQuality.MAJOR, chord.quality)
        assertTrue(chord.isDominant)
    }

    @Test
    fun `half diminished normalizes to one representation`() {
        assertEquals(parse("Bø7"), parse("Bm7b5"))
        assertTrue(parse("Bm7b5").isHalfDiminished)
    }

    @Test
    fun `fully diminished sevenths keep the diminished seventh`() {
        val chord = parse("Bdim7")
        assertEquals(ChordQuality.DIMINISHED, chord.quality)
        assertEquals(SeventhType.DIMINISHED, chord.seventh)
    }

    @Test
    fun `a dash after a degree is a flat sign, not minor`() {
        // C7-5 is a dominant with a lowered fifth. Reading the dash as "minor" would turn it into
        // a completely different chord.
        val chord = parse("C7-5")
        assertEquals(ChordQuality.MAJOR, chord.quality)
        assertEquals(SeventhType.MINOR, chord.seventh)
        assertTrue(Alteration.FLAT_FIVE in chord.alterations)
        assertEquals(parse("C7b5"), chord)
    }

    @Test
    fun `parses an altered dominant with a slash bass`() {
        val chord = parse("G13b9/Db")
        assertEquals(NoteSpelling(Letter.G), chord.root)
        assertEquals(SeventhType.MINOR, chord.seventh)
        assertTrue(13 in chord.extensions)
        assertTrue(Alteration.FLAT_NINE in chord.alterations)
        assertEquals(NoteSpelling(Letter.D, -1), chord.bass)
        assertTrue(chord.isSlash)
    }

    @Test
    fun `alt expands to the four altered tones`() {
        val chord = parse("C7alt")
        assertEquals(
            setOf(
                Alteration.FLAT_NINE,
                Alteration.SHARP_NINE,
                Alteration.SHARP_ELEVEN,
                Alteration.FLAT_THIRTEEN,
            ),
            chord.alterations,
        )
    }

    @Test
    fun `parses suspensions, sixths and additions`() {
        assertEquals(setOf(4), parse("Csus4").suspensions)
        assertEquals(ChordQuality.SUSPENDED, parse("G7sus4").quality)
        assertTrue(parse("C6").sixth)
        assertTrue(parse("C6/9").sixth)
        assertEquals(setOf(9), parse("C69").additions)
        assertEquals(setOf(9), parse("Cadd9").additions)
    }

    @Test
    fun `parses omissions and power chords`() {
        assertEquals(setOf(3), parse("C7no3").omissions)
        assertEquals(ChordQuality.POWER, parse("C5").quality)
    }

    @Test
    fun `a slash bass equal to the root is dropped`() {
        assertNull(parse("C/C").bass)
    }

    @Test
    fun `rejects text that is not a chord`() {
        assertNull(ChordParser.parse("Hello"))
        assertNull(ChordParser.parse("C~7"))
        assertNull(ChordParser.parse("Cmaj7zzz"))
    }

    @Test
    fun `recognizes the ways a chart writes no chord`() {
        assertTrue(ChordParser.isNoChord("N.C."))
        assertTrue(ChordParser.isNoChord("nc"))
        assertTrue(ChordParser.isNoChord(" "))
    }

    @Test
    fun `every symbol the formatter writes can be read back`() {
        val symbols = listOf(
            "C", "Cm", "Cmaj7", "Cm7", "C7", "Cm7b5", "Cdim7", "Caug", "Csus4", "C6",
            "C9", "C13", "Cm9", "Cmaj9", "C7b9", "C7#11", "G13b9/Db", "F#m7b5", "Bb7",
        )
        symbols.forEach { symbol ->
            val chord = parse(symbol)
            val rendered = ChordFormatter.format(chord, SymbolStyle.Ascii)
            val reparsed = ChordParser.parse(rendered)
            assertEquals("Round trip failed for $symbol (rendered as $rendered)", chord, reparsed)
        }
    }
}
