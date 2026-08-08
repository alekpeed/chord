package com.alekpeed.hearsay.core.model.music

import org.junit.Assert.assertEquals
import org.junit.Test

class ChordFormatterTest {

    private fun format(symbol: String, style: SymbolStyle = SymbolStyle.Ascii): String =
        ChordFormatter.format(requireNotNull(ChordParser.parse(symbol)), style)

    @Test
    fun `renders the common vocabulary`() {
        assertEquals("C", format("C"))
        assertEquals("Cm", format("Cm"))
        assertEquals("Cmaj7", format("CM7"))
        assertEquals("Cm7", format("C-7"))
        assertEquals("C7", format("C7"))
        assertEquals("Cdim", format("Cdim"))
        assertEquals("Cdim7", format("Cdim7"))
        assertEquals("Cm7b5", format("Cø7"))
        assertEquals("C5", format("C5"))
    }

    @Test
    fun `renders extensions at the highest written degree`() {
        assertEquals("C9", format("C9"))
        assertEquals("C13", format("C13"))
        assertEquals("Cmaj9", format("Cmaj9"))
        assertEquals("Cm11", format("Cm11"))
    }

    @Test
    fun `groups multiple alterations in parentheses`() {
        assertEquals("C7b9", format("C7b9"))
        assertEquals("C7(b9,#11)", format("C7b9#11"))
    }

    @Test
    fun `writes a slash bass last`() {
        assertEquals("G13b9/Db", format("G13b9/Db"))
        assertEquals("C/E", format("C/E"))
    }

    @Test
    fun `jazz style swaps the symbols without changing the chord`() {
        assertEquals("C–7", format("Cm7", SymbolStyle.Jazz))
        assertEquals("CΔ7", format("Cmaj7", SymbolStyle.Jazz))
        assertEquals("Cø7", format("Cm7b5", SymbolStyle.Jazz))
        assertEquals("C°7", format("Cdim7", SymbolStyle.Jazz))
    }

    @Test
    fun `unicode accidentals are used by default`() {
        assertEquals("B♭7", ChordFormatter.format(requireNotNull(ChordParser.parse("Bb7"))))
        assertEquals("F♯m7", ChordFormatter.format(requireNotNull(ChordParser.parse("F#m7"))))
    }

    @Test
    fun `a minor major seventh is unambiguous`() {
        assertEquals("Cm(maj7)", format("Cm(maj7)"))
    }

    @Test
    fun `sixths and six-nine chords`() {
        assertEquals("C6", format("C6"))
        assertEquals("C6/9", format("C6/9"))
    }
}
