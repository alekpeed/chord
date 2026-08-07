package com.alekpeed.hearsay.core.model.music

import org.junit.Assert.assertEquals
import org.junit.Test

class TranspositionTest {

    private fun chord(symbol: String) = requireNotNull(ChordParser.parse(symbol))

    @Test
    fun `transposing preserves quality, extensions and alterations`() {
        val original = chord("G13b9")
        val moved = original.transposedBy(2)
        assertEquals(original.quality, moved.quality)
        assertEquals(original.seventh, moved.seventh)
        assertEquals(original.extensions, moved.extensions)
        assertEquals(original.alterations, moved.alterations)
        assertEquals(NoteSpelling(Letter.A), moved.root)
    }

    @Test
    fun `a slash bass moves with the chord`() {
        val moved = chord("C/E").transposedBy(5)
        assertEquals(NoteSpelling(Letter.F), moved.root)
        assertEquals(NoteSpelling(Letter.A), moved.bass)
    }

    @Test
    fun `transposing wraps around the octave`() {
        assertEquals(NoteSpelling(Letter.C), chord("B").transposedBy(1).root)
        assertEquals(NoteSpelling(Letter.B), chord("C").transposedBy(-1).root)
    }

    @Test
    fun `spelling preference decides between enharmonics`() {
        assertEquals(NoteSpelling(Letter.C, 1), chord("C").transposedBy(1, preferFlats = false).root)
        assertEquals(NoteSpelling(Letter.D, -1), chord("C").transposedBy(1, preferFlats = true).root)
    }

    @Test
    fun `a flat chord keeps flat spelling by default`() {
        assertEquals(NoteSpelling(Letter.E, -1), chord("Bb").transposedBy(5).root)
    }

    @Test
    fun `pitch content moves by the same interval`() {
        val original = chord("Cmaj7").pitchClasses()
        val moved = chord("Cmaj7").transposedBy(3).pitchClasses()
        assertEquals(original.map { (it + 3) % 12 }.toSet(), moved)
    }

    @Test
    fun `round trip returns the original`() {
        val original = chord("F#m7b5")
        assertEquals(original, original.transposedBy(7).transposedBy(-7))
    }
}
