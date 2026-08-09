package com.alekpeed.hearsay.tools.desktop

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.music.Key
import com.alekpeed.hearsay.core.model.music.Mode
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChartRowBuilder
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which chord the window highlights at a given moment.
 *
 * The window exists to answer one question — does the highlight move with the music — so the
 * lookup behind it is worth testing even though the drawing is not. A highlight that blanks
 * between chords, or that lags a region boundary, would read as the very defect being looked for
 * and would send the search back into the analysis, where it would not be.
 */
class HighlightTest {

    private fun chord(symbol: String): Chord = requireNotNull(ChordParser.parse(symbol))

    private fun songOf(vararg spans: Triple<Long, Long, String>): Song {
        val events = spans.mapIndexed { index, (start, end, symbol) ->
            ChordEvent(
                id = "chord-$index",
                startMs = start,
                endMs = end,
                chord = chord(symbol),
                confidence = 0.5f,
                source = AnalysisSource.MACHINE,
                userConfirmed = false,
            )
        }
        val beats = (0 until 16).map { index ->
            BeatEvent(
                timeMs = index * 500L,
                beatInMeasure = index % 4 + 1,
                measureNumber = index / 4 + 1,
                confidence = 0.7f,
                source = AnalysisSource.MACHINE,
            )
        }
        val chart = SongChart.of(
            chordEvents = events,
            beats = beats,
            sections = emptyList(),
            tempoSegments = emptyList(),
            key = Key(NoteSpelling.fromPitchClass(0, false), Mode.MAJOR),
        )
        return Song(
            file = File("test.wav"),
            chart = chart,
            rows = ChartRowBuilder.build(chart),
            samples = FloatArray(0),
            sampleRate = 22_050,
            tempoBpm = 120f,
            keyLabel = "C major",
            warnings = emptyList(),
        )
    }

    @Test
    fun `the highlight is on the chord that is sounding`() {
        val song = songOf(
            Triple(0L, 2_000L, "C"),
            Triple(2_000L, 4_000L, "F"),
            Triple(4_000L, 6_000L, "G"),
        )
        assertEquals(0, song.rowAt(0))
        assertEquals(0, song.rowAt(1_999))
        assertEquals(1, song.rowAt(2_000))
        assertEquals(2, song.rowAt(5_999))
    }

    @Test
    fun `the highlight does not blank in a gap between chord regions`() {
        // Chord regions do not tile the recording — a rest, or a stretch the recognizer called no
        // chord, leaves a hole. Returning nothing there makes the highlight flicker off and back
        // on, which looks exactly like the timing defect this window was built to investigate.
        val song = songOf(
            Triple(0L, 2_000L, "C"),
            Triple(3_000L, 5_000L, "F"),
        )
        assertEquals("inside the gap the previous chord should stay lit", 0, song.rowAt(2_500))
        assertEquals(1, song.rowAt(3_000))
    }

    @Test
    fun `a moment before the first chord highlights nothing rather than crashing`() {
        val song = songOf(Triple(1_000L, 2_000L, "C"))
        assertTrue(song.rowAt(0) < 0)
    }

    @Test
    fun `the highlight never goes backwards as the position advances`() {
        val song = songOf(
            Triple(0L, 1_200L, "C"),
            Triple(1_200L, 2_500L, "Am"),
            Triple(2_500L, 4_000L, "F"),
            Triple(4_400L, 6_000L, "G"),
        )
        var previous = -1
        for (ms in 0L..6_000L step 25) {
            val row = song.rowAt(ms)
            assertTrue("row went backwards at ${ms}ms: $previous then $row", row >= previous)
            previous = row
        }
    }
}
