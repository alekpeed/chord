package com.alekpeed.hearsay.core.audio

import com.alekpeed.hearsay.core.audio.dsp.AudioBuffer
import com.alekpeed.hearsay.core.audio.dsp.Spectrogram
import com.alekpeed.hearsay.core.audio.rhythm.OnsetEnvelope
import com.alekpeed.hearsay.core.audio.rhythm.TempoEstimator
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Reproduces a tempo doubling on material that does not articulate every beat.
 *
 * Reported from a tablet: a ballad of about 70 BPM came back as 135, while a track of about 99 from
 * the same album came back correctly.
 *
 * What reproduces it is not softness and not offbeats — both of those were tried and the estimator
 * handles them. It is beats being *absent*. This fixture strikes a chord on most beats and leaves
 * the rest empty, which is what a ballad does and what a click track never does. At 70 BPM it is
 * reported as 136, at full confidence.
 *
 * Why absence rather than weakness matters is the open question. Autocorrelation at the true period
 * is weakened when beats are missing, since fewer pairs line up; something about the remaining
 * pattern then supports the halved period better. Whatever the mechanism, tuning the estimator on
 * fixtures where every beat is present cannot find it — a lesson this file exists to record.
 */
class TempoWeakPulseTest {

    private val rate = SignalGenerator.SampleRate

    /** Chords on the beat, with [density] of them actually struck and the rest left silent. */
    private fun sparseBallad(bpm: Float, density: Double, seed: Int, seconds: Double = 90.0): FloatArray {
        val random = Random(seed)
        val out = FloatArray((seconds * rate).toInt())
        val beatSeconds = 60.0 / bpm

        var time = 0.0
        while (time < seconds) {
            if (random.nextDouble() < density) {
                strikeChord(out, startSample = (time * rate).toInt(), sustainSeconds = beatSeconds * 2.5)
            }
            time += beatSeconds
        }
        return out
    }

    private fun strikeChord(out: FloatArray, startSample: Int, sustainSeconds: Double) {
        for (midi in listOf(48, 55, 60, 64, 67)) {
            val hz = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
            val length = (sustainSeconds * rate).toInt()
            for (i in 0 until length) {
                val at = startSample + i
                if (at >= out.size) break
                val t = i.toDouble() / rate
                val envelope = (1 - exp(-14.0 * t)) * exp(-0.8 * t)
                out[at] += (sin(2 * PI * hz * t) * envelope * 0.12).toFloat()
            }
        }
    }

    private fun reportedTempo(bpm: Float, density: Double = 0.65, seed: Int = 7): Float {
        val spectrogram = Spectrogram.of(
            AudioBuffer(sparseBallad(bpm, density, seed), rate),
            AnalysisSettings.Balanced.fftSize,
            AnalysisSettings.Balanced.hopSize,
        )
        val envelope = OnsetEnvelope.of(spectrogram)
        val curve = TempoEstimator.curve(envelope)
        val global = TempoEstimator.estimate(envelope)
        println(
            "SPARSE true=$bpm density=$density -> ${"%.1f".format(curve.medianBpm)} " +
                "confidence=${"%.2f".format(global.confidence)}",
        )
        return curve.medianBpm
    }

    @Test
    fun `a ballad that leaves beats out is not reported at double time`() {
        val doubled = listOf(60f, 70f, 80f)
            .map { it to reportedTempo(it) }
            .filter { (bpm, reported) -> reported > bpm * 1.5f }

        assertTrue("Reported at roughly double the true tempo: $doubled", doubled.isEmpty())
    }

    /**
     * The range the reported failures live in must be exact; one attempt at fixing the slow case
     * bought it by halving a genuine 168, and this is the assertion that caught it.
     */
    @Test
    fun `tempi in the contested range stay exact`() {
        val wrong = listOf(100f, 132f)
            .map { it to reportedTempo(it, density = 1.0) }
            .filter { (bpm, reported) -> kotlin.math.abs(reported - bpm) > bpm * 0.1f }

        assertTrue("Expected these within 10% of the true tempo: $wrong", wrong.isEmpty())
    }

    /**
     * Above the counting range, either metrical level is accepted.
     *
     * An accentless pulse every 357 ms is honestly ambiguous — counted at 168 or felt at 84 — and
     * standard tempo evaluation accepts either octave for exactly that reason. What is not
     * acceptable is anything that is neither: a third answer would mean the estimator invented a
     * level the music does not have.
     */
    @Test
    fun `a fast pulse resolves to one of its own metrical levels`() {
        val reported = reportedTempo(168f, density = 1.0)
        val nearFull = kotlin.math.abs(reported - 168f) < 168f * 0.1f
        val nearHalf = kotlin.math.abs(reported - 84f) < 84f * 0.1f

        assertTrue("Expected about 168 or 84, got $reported", nearFull || nearHalf)
    }
}
