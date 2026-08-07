package com.alekpeed.hearsay.core.model.timeline

import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.music.Key
import com.alekpeed.hearsay.core.model.music.Letter
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SymbolStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRowBuilderTest {

    private fun event(id: String, startMs: Long, endMs: Long, symbol: String?) = ChordEvent(
        id = id,
        startMs = startMs,
        endMs = endMs,
        chord = symbol?.let { ChordParser.parse(it) },
        confidence = 0.72f,
    )

    private val chart = SongChart.of(
        chordEvents = listOf(
            event("a", 0, 2000, "Dm7"),
            event("b", 2000, 4000, "G7"),
            event("c", 4000, 6000, "Cmaj7"),
            event("d", 6000, 8000, null),
        ),
        beats = listOf(
            BeatEvent(0, 1, 1), BeatEvent(2000, 1, 2), BeatEvent(4000, 1, 3), BeatEvent(6000, 1, 4),
        ),
        sections = listOf(
            SectionEvent("s1", "A", 0, 4000, 0),
            SectionEvent("s2", "B", 4000, 8000, 1),
        ),
        key = Key(NoteSpelling(Letter.C)),
    )

    @Test
    fun `builds one row per chord region, in order`() {
        val rows = ChartRowBuilder.build(chart)
        assertEquals(listOf("a", "b", "c", "d"), rows.map { it.eventId })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.index })
    }

    @Test
    fun `rows carry the measure and section they start in`() {
        val rows = ChartRowBuilder.build(chart)
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.measureNumber })
        assertEquals("A", rows[0].sectionLabel)
        assertEquals("B", rows[2].sectionLabel)
        assertTrue(rows[0].isSectionStart)
        assertFalse(rows[1].isSectionStart)
        assertTrue(rows[2].isSectionStart)
    }

    @Test
    fun `a region with no chord reads as no chord`() {
        assertEquals("N.C.", ChartRowBuilder.build(chart).last().displaySymbol)
    }

    @Test
    fun `transposition changes what is displayed`() {
        val rows = ChartRowBuilder.build(
            chart,
            ChartDisplayOptions(transposeSemitones = 2, style = SymbolStyle.Ascii),
        )
        assertEquals("Em7", rows[0].displaySymbol)
        assertEquals("A7", rows[1].displaySymbol)
        assertEquals("Dmaj7", rows[2].displaySymbol)
    }

    @Test
    fun `transposing by an octave is a no-op`() {
        val plain = ChartRowBuilder.build(chart, ChartDisplayOptions(style = SymbolStyle.Ascii))
        val octave = ChartRowBuilder.build(
            chart,
            ChartDisplayOptions(transposeSemitones = 12, style = SymbolStyle.Ascii),
        )
        assertEquals(plain.map { it.displaySymbol }, octave.map { it.displaySymbol })
    }

    @Test
    fun `roman and nashville notations use the project key`() {
        val roman = ChartRowBuilder.build(
            chart,
            ChartDisplayOptions(notation = ChordNotation.ROMAN, style = SymbolStyle.Ascii),
        )
        assertEquals(listOf("iim7", "V7", "Imaj7"), roman.take(3).map { it.displaySymbol })

        val nashville = ChartRowBuilder.build(
            chart,
            ChartDisplayOptions(notation = ChordNotation.NASHVILLE, style = SymbolStyle.Ascii),
        )
        assertEquals(listOf("2m7", "57", "1maj7"), nashville.take(3).map { it.displaySymbol })
    }

    @Test
    fun `simplifying drops the upper structure without touching the stored chord`() {
        val altered = SongChart.of(chordEvents = listOf(event("x", 0, 1000, "G13b9")))
        val simplified = ChartRowBuilder.build(
            altered,
            ChartDisplayOptions(simplify = true, style = SymbolStyle.Ascii),
        )
        assertEquals("G7", simplified.single().displaySymbol)
        assertEquals("G13b9", ChartRowBuilder.build(altered, ChartDisplayOptions(style = SymbolStyle.Ascii))
            .single().displaySymbol)
    }

    @Test
    fun `a slash bass is exposed separately for its own column`() {
        val slash = SongChart.of(chordEvents = listOf(event("x", 0, 1000, "C/E")))
        val row = ChartRowBuilder.build(slash, ChartDisplayOptions(style = SymbolStyle.Ascii)).single()
        assertEquals("E", row.bassLabel)
        assertEquals("C/E", row.displaySymbol)
    }
}
