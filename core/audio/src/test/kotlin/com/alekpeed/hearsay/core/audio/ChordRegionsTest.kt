package com.alekpeed.hearsay.core.audio

import com.alekpeed.hearsay.core.audio.harmony.dropUnplayableRegions
import com.alekpeed.hearsay.core.audio.harmony.joinRepeatedRegions
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is too short to be a chord.
 *
 * Reported from a tablet, in "Knocks Me Off My Feet" at 89 BPM: bar 13 opens with an E minor that
 * lasts 0.14 seconds — a fifth of a beat — between a Cmaj7 and a Gm. Nobody plays a harmony for a
 * fifth of a beat and no chart would write one, so it is the decoder changing its mind between two
 * frames rather than anything that happened in the room.
 *
 * The pass that already existed could not remove it. It absorbs a short region only into a neighbor
 * sharing its root, because it was written for a chord decaying across a bar line, where the
 * artifact is the same chord with one extra tone. Em shares no root with either side, so it stood.
 */
class ChordRegionsTest {

    /** 89 BPM, the tempo the defect was reported at. A beat is 674 ms. */
    private val beatMs = 674L

    private fun event(startMs: Long, endMs: Long, symbol: String?, confidence: Float = 0.5f) = ChordEvent(
        id = "chord-$startMs",
        startMs = startMs,
        endMs = endMs,
        chord = symbol?.let { requireNotNull(ChordParser.parse(it)) },
        confidence = confidence,
        source = AnalysisSource.MACHINE,
        userConfirmed = false,
    )

    private fun symbolsOf(events: List<ChordEvent>) = events.map { it.chord?.root?.letter?.toString() }

    @Test
    fun `a region lasting a fifth of a beat is not a chord`() {
        // Exactly the reported case: Cmaj7, then Em for 140 ms, then Gm.
        val cleaned = dropUnplayableRegions(
            listOf(
                event(30_000, 33_110, "Cmaj7"),
                event(33_110, 33_250, "Em"),
                event(33_250, 34_500, "Gm"),
            ),
            beatMs,
        )
        assertEquals("the Em should be gone", 2, cleaned.size)
        assertEquals(listOf("C", "G"), symbolsOf(cleaned))
    }

    @Test
    fun `the chord after an artifact keeps its own start`() {
        // The whole reason the earlier pass refused to cross roots: a chord that starts late no
        // longer lands where the player hears it, which is harder to read than the extra row was.
        // Giving the time backwards costs nothing, because that chord was sounding through it.
        val cleaned = dropUnplayableRegions(
            listOf(
                event(30_000, 33_110, "Cmaj7"),
                event(33_110, 33_250, "Em"),
                event(33_250, 34_500, "Gm"),
            ),
            beatMs,
        )
        assertEquals("Gm must still start where it started", 33_250, cleaned.last().startMs)
        assertEquals("and nothing may be left uncovered", 33_250, cleaned.first().endMs)
    }

    @Test
    fun `a real passing chord on half a beat survives`() {
        // The failure this must not become. A passing chord on the second half of a beat is music,
        // and smoothing it away is the specific thing this product exists not to do. Half a beat at
        // 89 BPM is 337 ms, comfortably above the floor.
        val cleaned = dropUnplayableRegions(
            listOf(
                event(0, 1_348, "F"),
                event(1_348, 1_685, "F#dim"),
                event(1_685, 3_033, "Gm"),
            ),
            beatMs,
        )
        assertEquals("the passing diminished belongs on the chart", 3, cleaned.size)
    }

    @Test
    fun `an artifact between two readings of one chord reveals they were one chord`() {
        val cleaned = joinRepeatedRegions(
            dropUnplayableRegions(
                listOf(
                    event(0, 2_000, "Cmaj7"),
                    event(2_000, 2_120, "Em"),
                    event(2_120, 4_000, "Cmaj7"),
                ),
                beatMs,
            ),
        )
        assertEquals("three rows saying one thing should be one row", 1, cleaned.size)
        assertEquals(0, cleaned.first().startMs)
        assertEquals(4_000, cleaned.first().endMs)
    }

    @Test
    fun `an artifact at the very start hands its time to what follows`() {
        val cleaned = dropUnplayableRegions(
            listOf(
                event(0, 100, "B"),
                event(100, 2_000, "Cmaj7"),
                event(2_000, 4_000, "F"),
            ),
            beatMs,
        )
        assertEquals(2, cleaned.size)
        assertEquals("the recording must not open on a hole", 0, cleaned.first().startMs)
        assertEquals(listOf("C", "F"), symbolsOf(cleaned))
    }

    @Test
    fun `a chart of nothing but artifacts is left alone rather than emptied`() {
        val events = listOf(
            event(0, 100, "C"),
            event(100, 200, "D"),
            event(200, 300, "E"),
        )
        assertTrue(
            "better the original reading than an empty chart",
            dropUnplayableRegions(events, beatMs).isNotEmpty(),
        )
    }

    @Test
    fun `the floor scales with tempo, because too short to be a chord is a musical statement`() {
        val events = listOf(
            event(0, 2_000, "C"),
            event(2_000, 2_250, "D"),
            event(2_250, 4_000, "F"),
        )
        // 250 ms is a third of a beat in a 89 BPM ballad — an artifact.
        assertEquals(2, dropUnplayableRegions(events, beatMs = 674).size)
        // The same 250 ms is more than a beat at 200 BPM, where it is a chord someone played.
        assertEquals(3, dropUnplayableRegions(events, beatMs = 300).size)
    }
}
