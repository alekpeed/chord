package com.alekpeed.hearsay.core.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Whether the grid sits on the beat or between the beats.
 *
 * Measured on a real recording: 77% of the tracker's beats carried less onset energy than the
 * midpoint between them, and shifting the whole grid half a period improved its alignment with the
 * audio sixfold. The tempo had been right the entire time. The grid was simply on the offbeats,
 * and it took the bar lines, the downbeat and every chord boundary with it.
 *
 * The cause is that the backtrace begins at one frame chosen from the last tenth of the recording,
 * and every beat inherits that frame's phase because the links it follows are a period apart. A
 * fade-out, a syncopation or a reverb tail at the end of a song therefore decides where beat one
 * falls four minutes earlier. Nothing checked that decision against the audio; now something does.
 *
 * Spacing is not the property under test here — [AudioAnalyzerTest] already covers that, and a grid
 * half a beat out has perfect spacing. What is under test is where the grid actually lands.
 */
class BeatPhaseTest {

    private val rate = SignalGenerator.SampleRate

    private fun beatTimesMs(bpm: Float, bars: Int): List<Long> =
        AudioAnalyzer(AnalysisSettings.Balanced)
            .analyze(SignalGenerator.clickTrack(bpm = bpm, bars = bars), 1, rate)
            .chart.beats.map { it.timeMs }

    /** Distance from each reported beat to the nearest click that was actually played. */
    private fun phaseErrorsMs(bpm: Float, bars: Int): List<Long> {
        val beatMs = 60_000.0 / bpm
        val clicks = (0 until bars * 4).map { (it * beatMs).toLong() }
        return beatTimesMs(bpm, bars)
            .filter { it in 0..clicks.last() }
            .map { beat -> clicks.minOf { abs(it - beat) } }
    }

    @Test
    fun `beats land on the clicks, not between them`() {
        val errors = phaseErrorsMs(bpm = 120f, bars = 16)
        assertTrue("Expected a grid", errors.size > 20)
        val median = errors.sorted()[errors.size / 2]

        // Half a beat at 120 BPM is 250 ms, so a grid on the offbeats scores around there. Anything
        // near zero is on the music. The bar between them is wide because this is a phase test and
        // not a precision one: the failure it guards against is off by half a beat, not by a frame.
        assertTrue("Median beat was ${median}ms from the nearest click in $errors", median < 80)
    }

    @Test
    fun `a slow tempo is not a special case`() {
        // The recording this was found on was a ballad, where half a beat is over half a second and
        // the error is correspondingly more obvious to a listener.
        val errors = phaseErrorsMs(bpm = 56f, bars = 12)
        assertTrue("Expected a grid", errors.size > 15)
        val median = errors.sorted()[errors.size / 2]
        assertTrue("Median beat was ${median}ms from the nearest click at 56 BPM", median < 140)
    }
}
