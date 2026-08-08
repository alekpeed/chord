package com.alekpeed.hearsay.core.audio

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordParser
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Synthesized audio with known ground truth.
 *
 * The analyzer cannot be proven against real recordings in a unit test — there is no legally
 * distributable fixture set and no way to run one on this machine. What can be proven is that
 * given a signal whose chords, tempo and bar lines are known exactly, the pipeline recovers them.
 * That is a real test of the algorithms even though it is not a test of real-world accuracy.
 */
object SignalGenerator {

    const val SampleRate = 22_050

    /**
     * Renders a chord progression as struck, decaying tones over a click track.
     *
     * The harmonic series matters: a pure sine per note would leave the chromagram's overtone
     * suppression untested, and real instruments are nothing like sine waves.
     */
    fun progression(
        symbols: List<String>,
        bpm: Float = 120f,
        beatsPerChord: Int = 4,
        repeats: Int = 2,
        withClick: Boolean = true,
        rootOctave: Int = 3,
    ): Pair<FloatArray, List<Chord>> {
        val beatSeconds = 60.0 / bpm
        val chordSeconds = beatSeconds * beatsPerChord
        val chords = buildList { repeat(repeats) { addAll(symbols) } }
            .map { requireNotNull(ChordParser.parse(it)) { "Unparseable test chord $it" } }

        val totalSamples = (chords.size * chordSeconds * SampleRate).toInt()
        val out = FloatArray(totalSamples)

        for ((index, chord) in chords.withIndex()) {
            val chordStart = (index * chordSeconds * SampleRate).toInt()
            val midiNotes = voiceChord(chord, rootOctave)

            // Restrike on every beat so there is an onset to find, with the downbeat loudest.
            for (beat in 0 until beatsPerChord) {
                val strikeStart = chordStart + (beat * beatSeconds * SampleRate).toInt()
                val amplitude = if (beat == 0) 0.9f else 0.55f
                for (midi in midiNotes) {
                    addTone(out, strikeStart, midiToHz(midi), amplitude / midiNotes.size, beatSeconds * 1.6)
                }
                if (withClick) addClick(out, strikeStart, if (beat == 0) 0.5f else 0.28f)
            }
        }

        var peak = 0f
        for (sample in out) peak = maxOf(peak, kotlin.math.abs(sample))
        if (peak > 0f) for (i in out.indices) out[i] /= peak
        return out to chords
    }

    /** A bare click track, for testing tempo and beat tracking without any harmony present. */
    fun clickTrack(bpm: Float, bars: Int, beatsPerBar: Int = 4): FloatArray {
        val beatSeconds = 60.0 / bpm
        val total = ((bars * beatsPerBar + 1) * beatSeconds * SampleRate).toInt()
        val out = FloatArray(total)
        for (beat in 0 until bars * beatsPerBar) {
            val start = (beat * beatSeconds * SampleRate).toInt()
            addClick(out, start, if (beat % beatsPerBar == 0) 1f else 0.6f)
        }
        return out
    }

    /**
     * A click track whose tempo moves steadily from [startBpm] to [endBpm].
     *
     * The point of the fixture is that no single tempo describes it. A tracker anchored to one
     * period can match the beginning or the end but not both, and the error accumulates — which is
     * exactly what a listener hears as the marker moving at the wrong speed.
     */
    fun acceleratingClickTrack(
        startBpm: Float,
        endBpm: Float,
        beats: Int,
        beatsPerBar: Int = 4,
    ): Pair<FloatArray, List<Double>> {
        val times = mutableListOf<Double>()
        var t = 0.0
        for (beat in 0 until beats) {
            times += t
            val position = beat.toDouble() / maxOf(1, beats - 1)
            val bpm = startBpm + (endBpm - startBpm) * position
            t += 60.0 / bpm
        }
        val total = ((t + 1.0) * SampleRate).toInt()
        val out = FloatArray(total)
        for ((index, time) in times.withIndex()) {
            addClick(out, (time * SampleRate).toInt(), if (index % beatsPerBar == 0) 1f else 0.6f)
        }
        return out to times
    }

    /** Spreads a chord across a plausible register: root low, remaining tones stacked above. */
    private fun voiceChord(chord: Chord, rootOctave: Int): List<Int> {
        val rootMidi = 12 * (rootOctave + 1) + chord.root.pitchClass
        val pitchClasses = chord.pitchClasses().toList()
        val voiced = mutableListOf(rootMidi)
        var previous = rootMidi
        for (pc in pitchClasses.filter { it != chord.root.pitchClass }) {
            var note = 12 * (rootOctave + 2) + pc
            while (note <= previous) note += 12
            voiced += note
            previous = note
        }
        return voiced
    }

    private fun midiToHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun addTone(
        out: FloatArray,
        startSample: Int,
        frequency: Double,
        amplitude: Float,
        durationSeconds: Double,
    ) {
        val length = (durationSeconds * SampleRate).toInt()
        val decay = 3.0 / durationSeconds
        for (i in 0 until length) {
            val index = startSample + i
            if (index >= out.size) break
            val t = i.toDouble() / SampleRate
            val envelope = exp(-decay * t)
            var value = 0.0
            for (partial in 1..5) {
                // Partials fall off faster than 1/n on a real struck or plucked string; 1/n was
                // bright enough that the fixture itself was unrepresentative.
                value += sin(2 * PI * frequency * partial * t) / partial.toDouble().pow(1.7)
            }
            out[index] += (value * envelope * amplitude).toFloat()
        }
    }

    /** A short noise burst — broadband, so it reads as percussive rather than pitched. */
    private fun addClick(out: FloatArray, startSample: Int, amplitude: Float) {
        val length = (0.02 * SampleRate).toInt()
        var state = (startSample + 1) * 1103515245L + 12345L
        for (i in 0 until length) {
            val index = startSample + i
            if (index >= out.size) break
            state = state * 6364136223846793005L + 1442695040888963407L
            val noise = ((state shr 33).toInt() / Int.MAX_VALUE.toFloat())
            val envelope = exp(-90.0 * i / SampleRate).toFloat()
            out[index] += noise * envelope * amplitude
        }
    }
}
