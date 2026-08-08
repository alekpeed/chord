package com.alekpeed.hearsay.core.audio

import com.alekpeed.hearsay.core.audio.dsp.AudioBuffer
import com.alekpeed.hearsay.core.audio.dsp.Spectrogram
import com.alekpeed.hearsay.core.audio.rhythm.BeatTracker
import com.alekpeed.hearsay.core.audio.rhythm.OnsetEnvelope
import com.alekpeed.hearsay.core.audio.rhythm.TempoEstimator
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Whether the beat grid follows a recording that does not hold one tempo.
 *
 * Reported from a tablet: the playing position moves at the wrong speed against the music. A
 * constant period cannot follow a curve, and the error is cumulative — it looks fine at the start
 * and is a bar out by the end, which is the hardest kind of wrong to see in a unit test unless the
 * test is written to look for drift specifically.
 */
class TempoCurveTest {

    private fun envelopeOf(samples: FloatArray): OnsetEnvelope {
        val spectrogram = Spectrogram.of(
            AudioBuffer(samples, SignalGenerator.SampleRate),
            AnalysisSettings.Balanced.fftSize,
            AnalysisSettings.Balanced.hopSize,
        )
        return OnsetEnvelope.of(spectrogram)
    }

    @Test
    fun `a steady recording still produces a steady curve`() {
        val envelope = envelopeOf(SignalGenerator.clickTrack(bpm = 120f, bars = 16))

        val curve = TempoEstimator.curve(envelope)

        assertTrue("Expected about 120, got ${curve.medianBpm}", abs(curve.medianBpm - 120f) < 8f)
        // A steady recording should not be described as changing tempo.
        assertTrue("Expected few tempo spans, got ${curve.segments().size}", curve.segments().size <= 3)
    }

    @Test
    fun `the curve follows a tempo that speeds up`() {
        val (samples, _) = SignalGenerator.acceleratingClickTrack(startBpm = 90f, endBpm = 130f, beats = 128)
        val envelope = envelopeOf(samples)

        val curve = TempoEstimator.curve(envelope)

        val early = curve.bpmAt(envelope.size / 6)
        val late = curve.bpmAt(envelope.size * 5 / 6)
        assertTrue("Expected the tempo to rise, got $early then $late", late > early + 10f)
    }

    @Test
    fun `beats stay with the music instead of drifting away from it`() {
        // The measurement that matters. Each detected beat is matched to the nearest real click;
        // a grid anchored to one period accumulates error, so the last beats are the ones that
        // expose it.
        val (samples, clickTimes) = SignalGenerator.acceleratingClickTrack(90f, 130f, beats = 128)
        val envelope = envelopeOf(samples)

        val curve = TempoEstimator.curve(envelope)
        val varying = BeatTracker.track(envelope, curve)
        val fixed = BeatTracker.track(envelope, TempoEstimator.estimate(envelope).bpm)

        val varyingError = meanErrorAgainst(clickTimes, varying, envelope)
        val fixedError = meanErrorAgainst(clickTimes, fixed, envelope)

        assertTrue(
            "A varying grid should track an accelerating recording at least as well as a fixed one: " +
                "varying ${"%.3f".format(varyingError)}s, fixed ${"%.3f".format(fixedError)}s",
            varyingError <= fixedError + 0.005,
        )
        assertTrue(
            "Beats should land close to the clicks, mean error was ${"%.3f".format(varyingError)}s",
            varyingError < 0.07,
        )
    }

    /** Mean distance in seconds from each detected beat to the nearest real click. */
    private fun meanErrorAgainst(
        clickTimes: List<Double>,
        beatFrames: List<Int>,
        envelope: OnsetEnvelope,
    ): Double {
        if (beatFrames.isEmpty()) return Double.MAX_VALUE
        var total = 0.0
        for (frame in beatFrames) {
            val time = frame * envelope.hopSeconds
            total += clickTimes.minOf { abs(it - time) }
        }
        return total / beatFrames.size
    }
}
