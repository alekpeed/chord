package com.alekpeed.hearsay.core.model.export

import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SectionEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartExporterTest {

    private val metadata = ExportMetadata(
        title = "Autumn Leaves",
        artist = "Somebody",
        keyLabel = "G minor",
        tempoBpm = 132f,
    )

    private fun chart(confidence: Float = 0.9f) = SongChart.of(
        chordEvents = listOf("Cm7", "F7", "Bbmaj7", "Ebmaj7").mapIndexed { index, symbol ->
            ChordEvent(
                id = "e$index",
                startMs = index * 2000L,
                endMs = (index + 1) * 2000L,
                chord = ChordParser.parse(symbol),
                confidence = confidence,
            )
        },
        beats = (0 until 16).map { BeatEvent(it * 500L, it % 4 + 1, it / 4 + 1) },
        sections = listOf(SectionEvent("s1", "Head", 0, 8000, 0)),
    )

    @Test
    fun `a text chart names the song and its key`() {
        val text = ChartExporter.toText(chart(), metadata)

        assertTrue(text.startsWith("Autumn Leaves"))
        assertTrue(text.contains("Somebody"))
        assertTrue(text.contains("Key: G minor"))
        assertTrue(text.contains("132 BPM"))
    }

    @Test
    fun `bars are laid out four to a line under their section`() {
        val text = ChartExporter.toText(chart(), metadata)

        assertTrue("Expected a section heading, got:\n$text", text.contains("[Head]"))
        val barLines = text.lines().filter { it.startsWith("|") }
        assertEquals(1, barLines.size)
        assertEquals(4, barLines.single().count { it == '|' } - 1)
    }

    @Test
    fun `an uncertain chord is marked rather than presented as fact`() {
        val text = ChartExporter.toText(chart(confidence = 0.3f), metadata)

        assertTrue("Expected uncertainty markers in:\n$text", text.contains("?"))
        assertTrue(text.contains("not confident"))
    }

    @Test
    fun `a confident chart carries no uncertainty markers on its bars`() {
        val text = ChartExporter.toText(chart(confidence = 0.95f), metadata)
        val barLine = text.lines().first { it.startsWith("|") }

        assertFalse(barLine.contains("?"))
    }

    @Test
    fun `an empty chart says so instead of producing an empty grid`() {
        val text = ChartExporter.toText(SongChart.Empty, metadata)
        assertTrue(text.contains("(no chords)"))
    }

    @Test
    fun `json keeps the structured chord, not only its display string`() {
        val document = Json.decodeFromString<ExportDocument>(ChartExporter.toJson(chart(), metadata))

        assertEquals(ChartExporter.FormatVersion, document.formatVersion)
        assertEquals(4, document.chords.size)

        val first = document.chords.first()
        assertEquals("Cm7", first.symbol)
        // The structure is what survives a round trip; the string is a rendering of it.
        assertEquals(ChordParser.parse("Cm7"), first.chord)
    }

    @Test
    fun `json keeps confidence and provenance`() {
        val document = Json.decodeFromString<ExportDocument>(ChartExporter.toJson(chart(0.42f), metadata))

        assertTrue(document.chords.all { it.confidence == 0.42f })
        assertTrue(document.chords.all { it.source == "MACHINE" })
        assertTrue(document.chords.none { it.userConfirmed })
    }

    @Test
    fun `json keeps the beat grid and sections`() {
        val document = Json.decodeFromString<ExportDocument>(ChartExporter.toJson(chart(), metadata))

        assertEquals(16, document.beats.size)
        assertEquals(listOf("Head"), document.sections.map { it.label })
    }

    @Test
    fun `an exported chart can be read back into the same chords`() {
        val document = Json.decodeFromString<ExportDocument>(ChartExporter.toJson(chart(), metadata))
        val reconstructed = document.chords.map { it.chord }

        assertEquals(chart().chordEvents.map { it.chord }, reconstructed)
    }
}
