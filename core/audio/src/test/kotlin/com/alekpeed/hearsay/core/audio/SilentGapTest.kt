package com.alekpeed.hearsay.core.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to the bar count across a rest.
 *
 * Reported from a tablet: a brief intro, a pause, then the verse — and the pause was counted as
 * beats, so the bar numbers marched on through silence. The tracker fills gaps wider than its
 * expected spacing, which is right when it under-detected a quiet passage and wrong when nothing
 * was playing. It could not tell those apart.
 */
class SilentGapTest {

    private val rate = SignalGenerator.SampleRate

    /** An intro, then real silence, then a verse — both parts at the same tempo. */
    private fun introPauseVerse(bpm: Float, silentSeconds: Double): FloatArray {
        val (intro, _) = SignalGenerator.progression(listOf("C", "G"), bpm = bpm, beatsPerChord = 4, repeats = 2)
        val (verse, _) = SignalGenerator.progression(
            listOf("C", "Am", "F", "G"), bpm = bpm, beatsPerChord = 4, repeats = 2,
        )
        val silence = FloatArray((silentSeconds * rate).toInt())
        val out = FloatArray(intro.size + silence.size + verse.size)
        intro.copyInto(out, 0)
        verse.copyInto(out, intro.size + silence.size)
        return out
    }

    private fun beatsInsideGap(result: AnalysisResult, fromMs: Long, toMs: Long) =
        result.chart.beats.count { it.timeMs in fromMs..toMs }

    @Test
    fun `a silent pause is not counted as beats`() {
        val bpm = 100f
        val (intro, _) = SignalGenerator.progression(listOf("C", "G"), bpm = bpm, beatsPerChord = 4, repeats = 2)
        val silentSeconds = 6.0
        val introMs = (intro.size * 1000L) / rate
        val gapEndMs = introMs + (silentSeconds * 1000).toLong()

        val result = AudioAnalyzer(AnalysisSettings.Balanced)
            .analyze(introPauseVerse(bpm, silentSeconds), 1, rate)

        // Six seconds at 100 BPM is ten beats. A grid that counts straight through the rest puts
        // most of them here; one that stops at the music puts almost none.
        val inGap = beatsInsideGap(result, introMs + 500, gapEndMs - 500)
        assertTrue(
            "Expected the silence to stay largely uncounted, found $inGap beats inside it",
            inGap <= 3,
        )
    }

    @Test
    fun `the music after the pause is still tracked`() {
        val bpm = 100f
        val result = AudioAnalyzer(AnalysisSettings.Balanced)
            .analyze(introPauseVerse(bpm, silentSeconds = 6.0), 1, rate)

        // Dropping the silence must not cost the verse: the point is to stop counting nothing, not
        // to stop counting.
        assertTrue("Expected a grid over the music, got ${result.chart.beats.size} beats", result.chart.beats.size > 20)
        assertTrue("Expected chords after the pause", result.chart.chordEvents.size >= 3)
    }

    @Test
    fun `a beat that was filled in is not reported as confidently as one that was heard`() {
        val result = AudioAnalyzer(AnalysisSettings.Balanced)
            .analyze(introPauseVerse(bpm = 100f, silentSeconds = 6.0), 1, rate)

        // Filled beats carry a lower confidence than heard ones, so nothing here should exceed
        // what a detected beat reports.
        val highest = result.chart.beats.maxOf { it.confidence }
        assertTrue("No beat should be more certain than a detected one, saw $highest", highest <= 0.7f)
    }

    @Test
    fun `continuous music is still gap-filled where the tracker misses a beat`() {
        // The repair this exists for must survive: quiet but continuous material should still get a
        // complete grid rather than holes wherever the tracker lost a beat.
        val (samples, _) = SignalGenerator.progression(
            listOf("C", "F", "G", "C"), bpm = 100f, beatsPerChord = 4, repeats = 4, withClick = false,
        )
        val result = AudioAnalyzer(AnalysisSettings.Balanced).analyze(samples, 1, rate)

        val spacings = result.chart.beats.zipWithNext { a, b -> b.timeMs - a.timeMs }
        val median = spacings.sorted()[spacings.size / 2]
        val holes = spacings.count { it > median * 1.8 }
        assertTrue("Expected a continuous grid, found $holes holes in ${spacings.size} gaps", holes <= 1)
    }
}
