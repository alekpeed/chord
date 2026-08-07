package com.alekpeed.hearsay.core.model.export

import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SectionEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.serialization.json.Json

/** Why a chart file could not be read, in terms the user can act on. */
sealed interface ImportChartFailure {
    /** The file is not a chart, or not JSON at all. */
    data class NotAChart(val message: String) : ImportChartFailure

    /** Written by a newer version of the format than this build understands. */
    data class UnsupportedVersion(val found: Int, val supported: Int) : ImportChartFailure

    /** Parsed, but contains no harmony — importing it would replace a chart with nothing. */
    data object NoChords : ImportChartFailure
}

class ImportChartException(val failure: ImportChartFailure) : Exception(failure.toString())

/**
 * Reads a chart produced by [ChartExporter], including one produced on a desktop.
 *
 * The desktop analyzer runs the same code as the app against the same recording with far more
 * memory, so the file it writes is not an approximation of an analysis — it is one. That is why
 * this reads the structured chord rather than the rendered symbol: `symbol` in the document is for
 * a human reading the file, and nothing here parses it back.
 *
 * Every imported chord keeps its own confidence and its own attribution. A chord the file records
 * as [AnalysisSource.USER] was corrected by hand and stays that way; anything else is still the
 * machine's opinion, and the user has to be able to disagree with it exactly as they would with an
 * analysis run on the tablet.
 */
object ChartImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun fromJson(text: String): Result<ImportedChart> {
        val document = runCatching { json.decodeFromString<ExportDocument>(text) }.getOrElse { error ->
            return Result.failure(
                ImportChartException(
                    ImportChartFailure.NotAChart(error.message ?: "The file could not be read as a chart"),
                ),
            )
        }

        // A newer writer may have added meaning this build would silently drop, so refusing beats
        // importing a chart that is quietly missing something the file actually said.
        if (document.formatVersion > ChartExporter.FormatVersion) {
            return Result.failure(
                ImportChartException(
                    ImportChartFailure.UnsupportedVersion(document.formatVersion, ChartExporter.FormatVersion),
                ),
            )
        }

        if (document.chords.isEmpty()) {
            return Result.failure(ImportChartException(ImportChartFailure.NoChords))
        }

        // A region that does not end after it starts would fail ChordEvent's own check, and one
        // bad row should not cost the whole import; it is dropped and the rest is kept.
        val chords = document.chords.filter { it.endMs > it.startMs }.mapIndexed { index, chord ->
            ChordEvent(
                id = "imported-chord-$index",
                startMs = chord.startMs,
                endMs = chord.endMs,
                chord = chord.chord,
                confidence = chord.confidence,
                source = sourceOf(chord.source),
                userConfirmed = chord.userConfirmed,
            )
        }
        if (chords.isEmpty()) {
            return Result.failure(ImportChartException(ImportChartFailure.NoChords))
        }

        val chart = SongChart.of(
            chordEvents = chords,
            beats = document.beats.map { BeatEvent(it.timeMs, it.beatInMeasure, it.measureNumber) },
            sections = document.sections.filter { it.endMs > it.startMs }
                .mapIndexed { index, section ->
                    SectionEvent(
                        id = "imported-section-$index",
                        label = section.label,
                        startMs = section.startMs,
                        endMs = section.endMs,
                        orderIndex = index,
                    )
                },
        )

        return Result.success(
            ImportedChart(
                chart = chart,
                title = document.title,
                artist = document.artist,
                keyLabel = document.key,
                tempoBpm = document.tempoBpm,
            ),
        )
    }

    /**
     * An unrecognized source becomes [AnalysisSource.IMPORTED] rather than a failure.
     *
     * All that is known about a source this build has not heard of is that it arrived in a file,
     * which is what IMPORTED means. Claiming the user confirmed it would be the dishonest answer.
     */
    private fun sourceOf(name: String): AnalysisSource =
        AnalysisSource.entries.firstOrNull { it.name == name } ?: AnalysisSource.IMPORTED
}

/** A chart read from a file, with the metadata the file carried alongside it. */
data class ImportedChart(
    val chart: SongChart,
    val title: String,
    val artist: String?,
    val keyLabel: String?,
    val tempoBpm: Float?,
)
