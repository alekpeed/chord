package com.alekpeed.hearsay.core.model.timeline

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.Key
import com.alekpeed.hearsay.core.model.music.NashvilleFormatter
import com.alekpeed.hearsay.core.model.music.RomanNumeralFormatter
import com.alekpeed.hearsay.core.model.music.SymbolStyle

/** How chord names are written in the table. The stored chord is the same in every case. */
enum class ChordNotation { SYMBOL, ROMAN, NASHVILLE }

/**
 * One row of the performance table: a chord region, already resolved against the beat grid and the
 * section map so the table itself does no music theory while scrolling.
 */
data class ChartRow(
    val index: Int,
    val eventId: String,
    val startMs: Long,
    val endMs: Long,
    val measureNumber: Int?,
    val beatInMeasure: Int?,
    val isMeasureStart: Boolean,
    val sectionLabel: String?,
    val isSectionStart: Boolean,
    val chord: Chord?,
    val displaySymbol: String,
    val bassLabel: String?,
    val confidence: Float,
    val userConfirmed: Boolean,
    val source: AnalysisSource,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Display settings that change how rows read without changing what is stored. */
data class ChartDisplayOptions(
    val notation: ChordNotation = ChordNotation.SYMBOL,
    val style: SymbolStyle = SymbolStyle.Default,
    val transposeSemitones: Int = 0,
    val simplify: Boolean = false,
)

object ChartRowBuilder {

    private const val NoChordSymbol = "N.C."

    fun build(chart: SongChart, options: ChartDisplayOptions = ChartDisplayOptions()): List<ChartRow> {
        val transposedKey = chart.key?.transposedBy(options.transposeSemitones)
        var previousMeasure: Int? = null
        var previousSection: String? = null

        return chart.chordEvents.mapIndexed { index, event ->
            val beat = chart.beatAt(event.startMs)
            val section = chart.sectionAt(event.startMs)
            val chord = event.chord
                ?.let { if (options.simplify) simplify(it) else it }
                ?.transposedBy(options.transposeSemitones, preferFlats = transposedKey?.prefersFlats ?: false)

            val row = ChartRow(
                index = index,
                eventId = event.id,
                startMs = event.startMs,
                endMs = event.endMs,
                measureNumber = beat?.measureNumber,
                beatInMeasure = beat?.beatInMeasure,
                isMeasureStart = beat != null && beat.measureNumber != previousMeasure,
                sectionLabel = section?.label,
                isSectionStart = section != null && section.label != previousSection,
                chord = chord,
                displaySymbol = render(chord, transposedKey, options),
                bassLabel = chord?.bass?.render(options.style.unicodeAccidentals),
                confidence = event.confidence,
                userConfirmed = event.userConfirmed,
                source = event.source,
            )
            previousMeasure = beat?.measureNumber ?: previousMeasure
            previousSection = section?.label ?: previousSection
            row
        }
    }

    private fun render(chord: Chord?, key: Key?, options: ChartDisplayOptions): String {
        if (chord == null) return NoChordSymbol
        return when (options.notation) {
            ChordNotation.SYMBOL -> ChordFormatter.format(chord, options.style)
            ChordNotation.ROMAN -> key?.let { RomanNumeralFormatter.format(chord, it, options.style) }
                ?: ChordFormatter.format(chord, options.style)
            ChordNotation.NASHVILLE -> key?.let { NashvilleFormatter.format(chord, it, options.style) }
                ?: ChordFormatter.format(chord, options.style)
        }
    }

    /**
     * Drops the upper structure for performance reading. The full chord stays in the database —
     * this only changes what the row says.
     */
    private fun simplify(chord: Chord): Chord = chord.copy(
        extensions = emptySet(),
        alterations = chord.alterations.filter { it.degree <= 5 }.toSet(),
        additions = emptySet(),
        omissions = emptySet(),
    )
}
