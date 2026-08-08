package com.alekpeed.hearsay.core.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * What happens to the bar count across a rest.
 *
 * Reported from a tablet: a brief intro, a pause, then the verse — and the pause was counted as
 * beats, so the bar numbers marched on through silence. The tracker fills gaps wider than its
 * expected spacing, which is right when it under-detected a quiet passage and wrong when nothing
 * was playing. It could not tell those apart.
 *
 * The pause here is deliberately not digital zero. The first fix for this shipped green against a
 * fixture of exact zeros and did nothing at all on the tablet, because a pause on a real recording
 * carries tape hiss, room tone and the tail of the chord that just stopped. A test the wrong fix
 * can pass is worse than no test, so the fixture below is quiet rather than absent.
 */
class SilentGapTest {

    private val rate = SignalGenerator.SampleRate

    /** Roughly 34 dB below the program material — a generous noise floor, not a subtle one. */
    private val hissLevel = 0.02

    /** An intro, then a pause with a noise floor and a decaying tail, then a verse at one tempo. */
    private fun introPauseVerse(bpm: Float, silentSeconds: Double): FloatArray {
        val (intro, _) = SignalGenerator.progression(listOf("C", "G"), bpm = bpm, beatsPerChord = 4, repeats = 2)
        val (verse, _) = SignalGenerator.progression(
            listOf("C", "Am", "F", "G"), bpm = bpm, beatsPerChord = 4, repeats = 2,
        )
        val pause = quietPause((silentSeconds * rate).toInt(), after = intro)
        val out = FloatArray(intro.size + pause.size + verse.size)
        intro.copyInto(out, 0)
        pause.copyInto(out, intro.size)
        verse.copyInto(out, intro.size + pause.size)
        return out
    }

    /**
     * What a room sounds like when nobody is playing.
     *
     * Hiss for the whole span, plus a reverberant tail of the last second of [after] decaying over
     * about a second. The tail is why level has to be measured rather than assumed: for the first
     * beat or so of the pause the recording genuinely is still sounding, and dropping that beat
     * would be as wrong as keeping all ten.
     */
    private fun quietPause(samples: Int, after: FloatArray): FloatArray {
        val out = FloatArray(samples)
        val programLevel = rms(after)
        val tailSamples = minOf(after.size, rate)
        var state = 88_172_645_463_325_252L
        for (i in out.indices) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            val noise = (state shr 40).toInt() / 8_388_608f
            out[i] = (noise * hissLevel * programLevel).toFloat()
            if (i < tailSamples) {
                val decay = exp(-4.0 * i / rate)
                out[i] += (after[after.size - tailSamples + i] * decay * 0.35).toFloat()
            }
        }
        return out
    }

    private fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample
        return sqrt(sum / samples.size)
    }

    @Test
    fun `the pause in this fixture is quiet, not empty`() {
        // Guards the fixture itself. If this ever passes trivially again, the tests below stop
        // meaning anything — which is exactly how the first attempt at this shipped broken.
        val pause = quietPause(rate * 3, after = SignalGenerator.clickTrack(bpm = 100f, bars = 2))
        val floor = pause.drop(rate * 2).maxOf { abs(it) }
        assertTrue("The pause must carry a noise floor, saw $floor", floor > 0f)
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
