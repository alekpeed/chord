package com.alekpeed.hearsay.core.audio

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Temporary probe: how many chords the chart reports against how many were played. */
class SegmentationProbe {

    private val rate = SignalGenerator.SampleRate
    private val bpm = 89.0
    private val beatMs = 60_000.0 / bpm

    // Rich close voicings on an electric-piano-like tone, which is the material that goes wrong.
    private val voicings = mapOf(
        "Fmaj7" to listOf(29, 60, 64, 65, 69, 72),
        "Dm9" to listOf(26, 60, 64, 65, 69, 74),
        "Gm7" to listOf(31, 58, 62, 65, 70),
        "C7" to listOf(24, 58, 60, 64, 67),
        "Bbmaj7" to listOf(22, 57, 60, 65, 69),
        "Am7" to listOf(21, 57, 60, 64, 67),
    )

    private fun ballad(symbols: List<String>, beatsPerChord: Double): FloatArray {
        val chordMs = beatMs * beatsPerChord
        val totalMs = chordMs * symbols.size + beatMs
        val out = FloatArray((totalMs / 1000.0 * rate).toInt())
        for ((index, symbol) in symbols.withIndex()) {
            val startMs = index * chordMs
            // Arpeggiated rather than block-struck: continuous articulation within one harmony is
            // exactly what makes the recognizer invent changes that were never played.
            val notes = voicings.getValue(symbol)
            var strike = 0
            while (strike * beatMs / 2 < chordMs) {
                val midi = notes[strike % notes.size]
                addTone(out, startMs + strike * beatMs / 2, 440.0 * 2.0.pow((midi - 69) / 12.0))
                strike++
            }
        }
        var beat = 0
        while (beat * beatMs < totalMs) {
            addClick(out, beat * beatMs, if (beat % 4 == 0) 0.55f else 0.3f)
            beat++
        }
        var peak = 0f
        for (s in out) peak = maxOf(peak, abs(s))
        if (peak > 0f) for (i in out.indices) out[i] /= peak
        return out
    }

    private fun addTone(out: FloatArray, startMs: Double, frequency: Double) {
        val start = (startMs / 1000.0 * rate).toInt()
        val length = (2.2 * beatMs / 1000.0 * rate).toInt()
        for (i in 0 until length) {
            val index = start + i
            if (index !in out.indices) break
            val t = i.toDouble() / rate
            var v = 0.0
            for (p in 1..5) v += sin(2 * PI * frequency * p * t) / p.toDouble().pow(1.6)
            out[index] += (v * exp(-1.6 * t) * 0.16).toFloat()
        }
    }

    private fun addClick(out: FloatArray, startMs: Double, amplitude: Float) {
        val start = (startMs / 1000.0 * rate).toInt()
        var state = (start + 1) * 1103515245L + 12345L
        for (i in 0 until (0.015 * rate).toInt()) {
            val index = start + i
            if (index !in out.indices) break
            state = state * 6364136223846793005L + 1442695040888963407L
            out[index] += (state shr 33).toInt() / Int.MAX_VALUE.toFloat() * exp(-95.0 * i / rate).toFloat() * amplitude
        }
    }

    private fun report(label: String, symbols: List<String>, beatsPerChord: Double) {
        val samples = ballad(symbols, beatsPerChord)
        val result = AudioAnalyzer(AnalysisSettings.Balanced).analyze(samples, 1, rate)
        val events = result.chart.chordEvents
        val shortest = events.minOfOrNull { it.endMs - it.startMs } ?: 0
        val perBeat = events.size.toDouble() / (symbols.size * beatsPerChord)
        println(
            "SEGPROBE $label played=${symbols.size} reported=${events.size} " +
                "ratio=${"%.2f".format(events.size.toDouble() / symbols.size)} " +
                "chordsPerBeat=${"%.2f".format(perBeat)} shortestMs=$shortest " +
                "bpm=${result.tempoBpm.toInt()}",
        )
    }

    @Test
    fun probe() {
        val eightBars = listOf("Fmaj7", "Dm9", "Gm7", "C7", "Fmaj7", "Bbmaj7", "Am7", "Dm9")
        report("1 chord per bar", eightBars, beatsPerChord = 4.0)
        report("1 chord per 2 beats", eightBars, beatsPerChord = 2.0)
    }
}
