package com.alekpeed.hearsay.core.model.timeline

import com.alekpeed.hearsay.core.model.music.ChordParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongChartTest {

    private fun chordEvent(id: String, startMs: Long, endMs: Long, symbol: String) = ChordEvent(
        id = id,
        startMs = startMs,
        endMs = endMs,
        chord = ChordParser.parse(symbol),
    )

    private val chart = SongChart.of(
        chordEvents = listOf(
            chordEvent("b", 2000, 4000, "Dm7"),
            chordEvent("a", 0, 2000, "Cmaj7"),
            chordEvent("c", 4000, 6000, "G7"),
        ),
        beats = listOf(
            BeatEvent(0, 1, 1), BeatEvent(500, 2, 1), BeatEvent(1000, 3, 1), BeatEvent(1500, 4, 1),
            BeatEvent(2000, 1, 2), BeatEvent(2500, 2, 2), BeatEvent(3000, 3, 2), BeatEvent(3500, 4, 2),
            BeatEvent(4000, 1, 3), BeatEvent(4500, 2, 3), BeatEvent(5000, 3, 3), BeatEvent(5500, 4, 3),
        ),
        sections = listOf(
            SectionEvent("s1", "Head", 0, 4000, 0),
            SectionEvent("s2", "Solo", 4000, 6000, 1),
        ),
        tempoSegments = listOf(TempoSegment(0, 6000, 120f)),
    )

    @Test
    fun `sorts events on construction regardless of input order`() {
        assertEquals(listOf("a", "b", "c"), chart.chordEvents.map { it.id })
    }

    @Test
    fun `a chord region is half open at its end`() {
        // The chord landing exactly on a boundary is the one starting there. Getting this backwards
        // makes the table flicker to the previous chord at every bar line.
        assertEquals("a", chart.chordAt(0)?.id)
        assertEquals("a", chart.chordAt(1999)?.id)
        assertEquals("b", chart.chordAt(2000)?.id)
        assertEquals("c", chart.chordAt(5999)?.id)
    }

    @Test
    fun `a position past the end is in no chord region`() {
        assertEquals(-1, chart.indexOfChordAt(6000))
        assertNull(chart.chordAt(6000))
        assertNull(chart.chordAt(-1))
    }

    @Test
    fun `a gap between regions resolves to no chord, and seeks forward to the next`() {
        val sparse = SongChart.of(
            chordEvents = listOf(
                chordEvent("a", 0, 1000, "C"),
                chordEvent("b", 3000, 4000, "F"),
            ),
        )
        assertNull(sparse.chordAt(2000))
        assertEquals(1, sparse.indexOfChordAtOrAfter(2000))
        assertEquals(0, sparse.indexOfChordAtOrAfter(500))
    }

    @Test
    fun `measures come from the beat grid`() {
        assertEquals(1, chart.measureNumberAt(0))
        assertEquals(1, chart.measureNumberAt(1999))
        assertEquals(2, chart.measureNumberAt(2000))
        assertEquals(3, chart.measureNumberAt(5999))
        assertNull(chart.measureNumberAt(-1))
    }

    @Test
    fun `next measure jumps to the following downbeat`() {
        assertEquals(2000L, chart.nextMeasureStartMs(0))
        assertEquals(2000L, chart.nextMeasureStartMs(1999))
        assertEquals(4000L, chart.nextMeasureStartMs(2000))
        assertNull(chart.nextMeasureStartMs(4000))
    }

    @Test
    fun `previous measure restarts the current bar once you are into it`() {
        // Just after a downbeat, "previous" means the bar before. Later in the bar it means
        // "start this bar again", which is what a transport button does on hardware.
        assertEquals(0L, chart.previousMeasureStartMs(2100))
        assertEquals(2000L, chart.previousMeasureStartMs(2500))
        assertEquals(4000L, chart.previousMeasureStartMs(4600))
        assertEquals(0L, chart.previousMeasureStartMs(2200, restartWindowMs = 400))
        assertEquals(2000L, chart.previousMeasureStartMs(2200, restartWindowMs = 100))
    }

    @Test
    fun `previous measure at the very start stays at the start`() {
        assertEquals(0L, chart.previousMeasureStartMs(100))
        assertEquals(0L, chart.previousMeasureStartMs(0))
    }

    @Test
    fun `sections and tempo are looked up by position`() {
        assertEquals("Head", chart.sectionAt(0)?.label)
        assertEquals("Head", chart.sectionAt(3999)?.label)
        assertEquals("Solo", chart.sectionAt(4000)?.label)
        assertNull(chart.sectionAt(6000))
        assertEquals(120f, chart.tempoAt(1000))
    }

    @Test
    fun `duration covers the last event that ends`() {
        assertEquals(6000L, chart.durationMs)
        assertEquals(0L, SongChart.Empty.durationMs)
    }

    @Test
    fun `lookup is correct across a large chart`() {
        val events = (0 until 2000).map { index ->
            chordEvent("e$index", index * 1000L, (index + 1) * 1000L, "C")
        }
        val large = SongChart.of(chordEvents = events)
        assertEquals(0, large.indexOfChordAt(0))
        assertEquals(999, large.indexOfChordAt(999_500))
        assertEquals(1999, large.indexOfChordAt(1_999_999))
        assertEquals(-1, large.indexOfChordAt(2_000_000))
    }
}
