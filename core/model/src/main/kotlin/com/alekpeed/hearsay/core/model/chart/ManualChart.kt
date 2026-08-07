package com.alekpeed.hearsay.core.model.chart

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.Key
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType
import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SectionEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import com.alekpeed.hearsay.core.model.timeline.TempoSegment
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Builds charts by hand rather than by analysis.
 *
 * Two uses. A user who knows a tune can lay a grid over it and type the changes in — that is a real
 * feature, not a placeholder. And the performance view needs something to follow while the analysis
 * pipeline does not exist yet. Everything produced here is marked [AnalysisSource.USER] or
 * [AnalysisSource.SEED] so it can never be mistaken for something a model heard.
 */
object ManualChart {

    /**
     * Lays a steady beat grid across [durationMs] and gives every measure an empty chord region for
     * the user to fill in.
     */
    fun blankGrid(
        durationMs: Long,
        bpm: Float,
        beatsPerMeasure: Int = 4,
        firstDownbeatMs: Long = 0L,
    ): SongChart {
        require(bpm > 0) { "Tempo must be positive, was $bpm" }
        require(beatsPerMeasure > 0) { "A measure needs at least one beat, was $beatsPerMeasure" }

        val beatMs = 60_000.0 / bpm
        val totalBeats = max(0, ((durationMs - firstDownbeatMs) / beatMs).toInt())

        val beats = (0 until totalBeats).map { index ->
            BeatEvent(
                timeMs = firstDownbeatMs + (index * beatMs).roundToLong(),
                beatInMeasure = index % beatsPerMeasure + 1,
                measureNumber = index / beatsPerMeasure + 1,
                confidence = 1f,
                source = AnalysisSource.USER,
            )
        }

        val downbeats = beats.filter { it.isDownbeat }
        val chords = downbeats.mapIndexed { index, downbeat ->
            val end = downbeats.getOrNull(index + 1)?.timeMs ?: max(downbeat.timeMs + 1, durationMs)
            ChordEvent(
                id = "measure-${index + 1}",
                startMs = downbeat.timeMs,
                endMs = end,
                chord = null,
                confidence = 1f,
                source = AnalysisSource.USER,
                userConfirmed = false,
            )
        }

        return SongChart.of(
            chordEvents = chords,
            beats = beats,
            tempoSegments = listOf(TempoSegment(0, max(durationMs, 1), bpm, 1f)),
        )
    }

    /**
     * A twelve-bar blues repeated to fill [durationMs].
     *
     * Used by the demo project and by tests: a chart with real harmonic movement, measure-aligned
     * boundaries and section changes for the table to get right.
     */
    fun twelveBarBlues(
        durationMs: Long,
        bpm: Float = 120f,
        keyRoot: String = "F",
    ): SongChart {
        val grid = blankGrid(durationMs, bpm)
        val tonic = requireNotNull(NoteSpelling.parse(keyRoot)) { "Unparseable key root: $keyRoot" }

        // I / IV / I / I  ·  IV / IV / I / I  ·  V / IV / I / V
        val degrees = listOf(0, 5, 0, 0, 5, 5, 0, 0, 7, 5, 0, 7)

        val chords = grid.chordEvents.mapIndexed { index, event ->
            event.copy(
                chord = Chord(
                    root = tonic.transposedBy(degrees[index % degrees.size], preferFlats = true),
                    seventh = SeventhType.MINOR,
                ),
                source = AnalysisSource.SEED,
            )
        }

        val sections = grid.chordEvents.chunked(degrees.size).mapIndexed { index, measures ->
            SectionEvent(
                id = "chorus-${index + 1}",
                label = "Chorus ${index + 1}",
                startMs = measures.first().startMs,
                endMs = measures.last().endMs,
                orderIndex = index,
                confidence = 1f,
                source = AnalysisSource.SEED,
            )
        }

        return SongChart.of(
            chordEvents = chords,
            beats = grid.beats,
            sections = sections,
            tempoSegments = grid.tempoSegments,
            key = Key(tonic),
        )
    }
}
