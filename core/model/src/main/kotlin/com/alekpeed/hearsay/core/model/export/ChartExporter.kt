package com.alekpeed.hearsay.core.model.export

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.SymbolStyle
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What an export says about where it came from, so a shared chart is never anonymous. */
data class ExportMetadata(
    val title: String,
    val artist: String?,
    val keyLabel: String?,
    val tempoBpm: Float?,
    val appVersion: String = "0.2.0",
)

/**
 * Turns a chart into something that leaves the app.
 *
 * Two formats with different jobs. The text chart is for a human — bars grouped into lines, the way
 * a lead sheet reads. The JSON is for a machine and keeps the structured chords, confidences and
 * sources intact, because an export that flattens everything to display strings throws away
 * exactly what makes this app's data worth having.
 */
object ChartExporter {

    private const val BarsPerLine = 4
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * A readable chord chart.
     *
     * Bars are laid out four to a line with section headings, and a chord the app was unsure about
     * is marked, because a chart that hides its uncertainty is worse than one that admits it.
     */
    fun toText(
        chart: SongChart,
        metadata: ExportMetadata,
        style: SymbolStyle = SymbolStyle.Default,
        uncertainBelow: Float = 0.6f,
    ): String = buildString {
        appendLine(metadata.title)
        metadata.artist?.let { appendLine(it) }
        val details = listOfNotNull(
            metadata.keyLabel?.let { "Key: $it" },
            metadata.tempoBpm?.let { "Tempo: ${it.toInt()} BPM" },
        )
        if (details.isNotEmpty()) appendLine(details.joinToString("   "))
        appendLine()

        if (chart.chordEvents.isEmpty()) {
            appendLine("(no chords)")
            return@buildString
        }

        var currentSection: String? = null
        val line = StringBuilder()
        var barsOnLine = 0

        for (event in chart.chordEvents) {
            val section = chart.sectionAt(event.startMs)?.label
            if (section != currentSection) {
                if (line.isNotBlank()) {
                    appendLine(line.toString().trimEnd())
                    line.clear()
                    barsOnLine = 0
                }
                currentSection = section
                section?.let {
                    appendLine()
                    appendLine("[$it]")
                }
            }

            val symbol = event.chord?.let { ChordFormatter.format(it, style) } ?: "N.C."
            val marked = if (!event.userConfirmed && event.confidence < uncertainBelow) "$symbol?" else symbol
            line.append("| ").append(marked.padEnd(10))
            barsOnLine++

            if (barsOnLine == BarsPerLine) {
                appendLine(line.toString().trimEnd() + " |")
                line.clear()
                barsOnLine = 0
            }
        }
        if (line.isNotBlank()) appendLine(line.toString().trimEnd() + " |")

        appendLine()
        appendLine("? marks a chord the analysis was not confident about.")
        appendLine("Exported from Hearsay ${metadata.appVersion}.")
    }

    /** The portable form: structured chords, timing, confidence and provenance, nothing lost. */
    fun toJson(chart: SongChart, metadata: ExportMetadata): String {
        val document = ExportDocument(
            formatVersion = FormatVersion,
            title = metadata.title,
            artist = metadata.artist,
            key = metadata.keyLabel,
            tempoBpm = metadata.tempoBpm,
            chords = chart.chordEvents.map { event ->
                ExportChord(
                    startMs = event.startMs,
                    endMs = event.endMs,
                    symbol = event.chord?.let { ChordFormatter.format(it) },
                    chord = event.chord,
                    confidence = event.confidence,
                    source = event.source.name,
                    userConfirmed = event.userConfirmed,
                )
            },
            beats = chart.beats.map { ExportBeat(it.timeMs, it.beatInMeasure, it.measureNumber) },
            sections = chart.sections.map { ExportSection(it.label, it.startMs, it.endMs) },
        )
        return json.encodeToString(document)
    }

    const val FormatVersion = 1
}

@Serializable
data class ExportDocument(
    val formatVersion: Int,
    val title: String,
    val artist: String?,
    val key: String?,
    val tempoBpm: Float?,
    val chords: List<ExportChord>,
    val beats: List<ExportBeat>,
    val sections: List<ExportSection>,
)

@Serializable
data class ExportChord(
    val startMs: Long,
    val endMs: Long,
    val symbol: String?,
    val chord: Chord?,
    val confidence: Float,
    val source: String,
    val userConfirmed: Boolean,
)

@Serializable
data class ExportBeat(val timeMs: Long, val beatInMeasure: Int, val measureNumber: Int)

@Serializable
data class ExportSection(val label: String, val startMs: Long, val endMs: Long)
