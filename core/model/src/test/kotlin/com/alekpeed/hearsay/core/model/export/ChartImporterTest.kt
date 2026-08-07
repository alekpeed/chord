package com.alekpeed.hearsay.core.model.export

import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SectionEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import exists so an analysis run on a desktop can be read on the tablet.
 *
 * The round trip is the test that matters: whatever the desktop writes, the tablet has to read back
 * as the same harmony. A chord that survives as a rendered symbol but loses its structure would
 * still display correctly and would be wrong the moment anybody transposed it.
 */
class ChartImporterTest {

    private val metadata = ExportMetadata(
        title = "Autumn Leaves",
        artist = "Somebody",
        keyLabel = "G minor",
        tempoBpm = 132f,
    )

    private fun chart() = SongChart.of(
        chordEvents = listOf("Cm7", "F7", "Bbmaj7", "Ebmaj7", "Am7b5", "D7alt").mapIndexed { index, symbol ->
            ChordEvent(
                id = "e$index",
                startMs = index * 2000L,
                endMs = (index + 1) * 2000L,
                chord = ChordParser.parse(symbol),
                confidence = 0.4f + index * 0.1f,
                source = if (index == 2) AnalysisSource.USER else AnalysisSource.MACHINE,
                userConfirmed = index == 2,
            )
        },
        beats = (0 until 24).map { BeatEvent(it * 500L, it % 4 + 1, it / 4 + 1) },
        sections = listOf(SectionEvent("s1", "Head", 0, 8000, 0), SectionEvent("s2", "Solo", 8000, 12000, 1)),
    )

    @Test
    fun `a chart survives the trip out to a file and back`() {
        val original = chart()

        val imported = ChartImporter.fromJson(ChartExporter.toJson(original, metadata)).getOrThrow()

        assertEquals(original.chordEvents.size, imported.chart.chordEvents.size)
        original.chordEvents.zip(imported.chart.chordEvents).forEach { (before, after) ->
            assertEquals(before.chord, after.chord)
            assertEquals(before.startMs, after.startMs)
            assertEquals(before.endMs, after.endMs)
            assertEquals(before.confidence, after.confidence, 1e-6f)
        }
    }

    @Test
    fun `chords come back structured, not as symbols that were parsed again`() {
        // The point of the structural round trip: an altered dominant renders one way and means
        // something a parser reading the symbol could easily reconstruct differently.
        val original = chart()

        val imported = ChartImporter.fromJson(ChartExporter.toJson(original, metadata)).getOrThrow()

        val before = original.chordEvents.last().chord!!
        val after = imported.chart.chordEvents.last().chord!!
        assertEquals(before, after)
        assertEquals(ChordFormatter.format(before), ChordFormatter.format(after))
    }

    @Test
    fun `a chord the user corrected is still theirs after importing`() {
        val imported = ChartImporter.fromJson(ChartExporter.toJson(chart(), metadata)).getOrThrow()

        val corrected = imported.chart.chordEvents[2]
        assertEquals(AnalysisSource.USER, corrected.source)
        assertTrue(corrected.userConfirmed)
    }

    @Test
    fun `beats and sections come back`() {
        val imported = ChartImporter.fromJson(ChartExporter.toJson(chart(), metadata)).getOrThrow()

        assertEquals(24, imported.chart.beats.size)
        assertEquals(listOf("Head", "Solo"), imported.chart.sections.map { it.label })
        assertEquals(4, imported.chart.beats[3].beatInMeasure)
    }

    @Test
    fun `metadata the file carried is available to the caller`() {
        val imported = ChartImporter.fromJson(ChartExporter.toJson(chart(), metadata)).getOrThrow()

        assertEquals("Autumn Leaves", imported.title)
        assertEquals("Somebody", imported.artist)
        assertEquals("G minor", imported.keyLabel)
        assertEquals(132f, imported.tempoBpm!!, 1e-6f)
    }

    @Test
    fun `something that is not a chart is refused rather than half-read`() {
        val failure = ChartImporter.fromJson("""{"hello":"world"}""").exceptionOrNull()

        assertTrue(failure is ImportChartException)
        assertTrue((failure as ImportChartException).failure is ImportChartFailure.NotAChart)
    }

    @Test
    fun `a file that is not json at all is refused`() {
        val failure = ChartImporter.fromJson("this is a text lead sheet, not the json one").exceptionOrNull()

        assertTrue((failure as ImportChartException).failure is ImportChartFailure.NotAChart)
    }

    @Test
    fun `a newer format version is refused rather than partly understood`() {
        // Importing it would silently drop whatever the newer writer added, and the user would have
        // no way to tell that the chart on screen is missing something the file actually said.
        // The exporter pretty-prints, so match the key rather than a guess at its spacing.
        val text = ChartExporter.toJson(chart(), metadata)
            .replace(Regex("\"formatVersion\"\\s*:\\s*\\d+"), "\"formatVersion\": 99")

        val failure = ChartImporter.fromJson(text).exceptionOrNull()

        val reason = (failure as ImportChartException).failure
        assertTrue(reason is ImportChartFailure.UnsupportedVersion)
        assertEquals(99, (reason as ImportChartFailure.UnsupportedVersion).found)
    }

    @Test
    fun `a chart with no harmony is refused rather than replacing one that has some`() {
        val empty = ChartExporter.toJson(
            SongChart.of(beats = listOf(BeatEvent(0, 1, 1))),
            metadata,
        )

        val failure = ChartImporter.fromJson(empty).exceptionOrNull()

        assertTrue((failure as ImportChartException).failure is ImportChartFailure.NoChords)
    }
}
