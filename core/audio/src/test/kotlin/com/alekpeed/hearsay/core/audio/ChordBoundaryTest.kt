package com.alekpeed.hearsay.core.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Whether the chart's chord changes land where the harmony changes.
 *
 * This is the moment the player actually watches: the highlighted chord moving. For as long as
 * chord boundaries were snapped to the beat grid, a change could sit up to half a beat from where
 * it was played, and an anticipated chord — struck ahead of the bar, which is everywhere in this
 * repertoire — could not be represented at all. The user's report was exactly that: the highlight
 * does not move with the chords, whatever the tempo readout says.
 *
 * So the fixture here anticipates every change by 240 ms while the click stays on the grid, and
 * the assertions measure the distance between where the chart says the chord changed and where it
 * actually did. Quantized boundaries fail this by construction; refined ones pass.
 */
class ChordBoundaryTest {

    private val rate = SignalGenerator.SampleRate
    private val bpm = 100.0
    private val beatMs = 60_000.0 / bpm
    private val beatsPerChord = 4
    private val anticipationMs = 240.0

    private val triads = mapOf(
        "C" to listOf(60, 64, 67),
        "F" to listOf(65, 69, 72),
        "G" to listOf(67, 71, 74),
        "Am" to listOf(57, 60, 64),
    )

    /**
     * Chords struck [anticipationMs] ahead of their bar, over a click that never leaves the grid.
     *
     * The click keeps the beat tracker honest — the grid this fixture produces is right, and the
     * chords are still early against it, which is the situation being tested. Overtones are
     * deliberate, as everywhere else in these tests: pure sines would flatter the chromagram.
     */
    private fun anticipatedProgression(symbols: List<String>): Pair<FloatArray, List<Double>> {
        val chordMs = beatMs * beatsPerChord
        val totalMs = chordMs * symbols.size + beatMs
        val out = FloatArray((totalMs / 1000.0 * rate).toInt())
        val changesMs = mutableListOf<Double>()

        for ((index, symbol) in symbols.withIndex()) {
            val gridStartMs = index * chordMs
            val startMs = if (index == 0) 0.0 else gridStartMs - anticipationMs
            if (index > 0) changesMs += startMs
            val endMs = if (index == symbols.size - 1) totalMs else (index + 1) * chordMs - anticipationMs

            // Struck at its true start and restruck each beat after, so the chroma carries it for
            // its whole duration rather than only at the attack.
            var strikeMs = startMs
            while (strikeMs < endMs - 1.0) {
                for (midi in triads.getValue(symbol)) {
                    addTone(out, strikeMs, 440.0 * 2.0.pow((midi - 69) / 12.0), amplitude = 0.25f)
                }
                strikeMs += beatMs
            }
        }
        var beatIndex = 0
        while (beatIndex * beatMs < totalMs) {
            addClick(out, beatIndex * beatMs, if (beatIndex % beatsPerChord == 0) 0.9f else 0.55f)
            beatIndex++
        }

        var peak = 0f
        for (sample in out) peak = maxOf(peak, abs(sample))
        if (peak > 0f) for (i in out.indices) out[i] /= peak
        return out to changesMs
    }

    private fun addTone(out: FloatArray, startMs: Double, frequency: Double, amplitude: Float) {
        val start = (startMs / 1000.0 * rate).toInt()
        val length = (1.4 * beatMs / 1000.0 * rate).toInt()
        for (i in 0 until length) {
            val index = start + i
            if (index !in out.indices) break
            val t = i.toDouble() / rate
            var value = 0.0
            for (partial in 1..4) {
                value += sin(2 * PI * frequency * partial * t) / partial.toDouble().pow(1.7)
            }
            out[index] += (value * exp(-2.5 * t) * amplitude).toFloat()
        }
    }

    private fun addClick(out: FloatArray, startMs: Double, amplitude: Float) {
        val start = (startMs / 1000.0 * rate).toInt()
        var state = (start + 1) * 1103515245L + 12345L
        for (i in 0 until (0.02 * rate).toInt()) {
            val index = start + i
            if (index !in out.indices) break
            state = state * 6364136223846793005L + 1442695040888963407L
            val noise = (state shr 33).toInt() / Int.MAX_VALUE.toFloat()
            out[index] += noise * exp(-90.0 * i / rate).toFloat() * amplitude
        }
    }

    private fun boundaryErrorsMs(symbols: List<String>): List<Double> {
        val (samples, changesMs) = anticipatedProgression(symbols)
        val result = AudioAnalyzer(AnalysisSettings.Balanced).analyze(samples, 1, rate)
        val boundaries = result.chart.chordEvents.drop(1).map { it.startMs }
        assertTrue("The analysis found no chord changes at all", boundaries.isNotEmpty())
        return changesMs.map { change -> boundaries.minOf { abs(it - change) } }
    }

    @Test
    fun `an anticipated chord change is placed where it is played, not on the beat`() {
        val errors = boundaryErrorsMs(listOf("C", "F", "G", "Am", "C", "F", "G", "C"))
        val median = errors.sorted()[errors.size / 2]

        // The changes are 240 ms ahead of the grid, so a chart that quantizes to beats cannot get
        // closer than that. Halfway is the pass line: close enough that the highlight reads as
        // moving with the music, far enough from the frame size that this is not asking for magic.
        assertTrue("Median boundary error was ${median}ms against $errors", median <= 120.0)
    }

    @Test
    fun `refinement does not tear boundaries that are already on the grid`() {
        // The same progression played squarely on the beat. Refinement has nothing to move here,
        // and moving anything anyway — jitter for its own sake — would be a regression against the
        // grid the fixture defines.
        val (samples, _) = SignalGenerator.progression(
            listOf("C", "F", "G", "C"), bpm = bpm.toFloat(), beatsPerChord = beatsPerChord, repeats = 2,
        )
        val result = AudioAnalyzer(AnalysisSettings.Balanced).analyze(samples, 1, rate)
        val chordMs = beatMs * beatsPerChord

        val errors = result.chart.chordEvents.drop(1).map { event ->
            val nearest = Math.round(event.startMs / chordMs) * chordMs
            abs(event.startMs - nearest)
        }
        assertTrue("Expected chord changes to exist", errors.isNotEmpty())
        val median = errors.sorted()[errors.size / 2]
        assertTrue("On-grid changes drifted: median ${median}ms off the bar in $errors", median <= 120.0)
    }

    @Test
    fun `chord spans stay contiguous after refinement`() {
        val (samples, _) = anticipatedProgression(listOf("C", "F", "G", "Am", "C", "F"))
        val result = AudioAnalyzer(AnalysisSettings.Balanced).analyze(samples, 1, rate)

        // The highlight follows chordAt(positionMs). A gap or an overlap between spans is a moment
        // where the highlight goes blank or flickers between two rows, so neither may exist.
        result.chart.chordEvents.zipWithNext().forEach { (a, b) ->
            assertTrue("Chord spans must not overlap or leave gaps: ${a.endMs} vs ${b.startMs}", a.endMs == b.startMs)
        }
    }
}
