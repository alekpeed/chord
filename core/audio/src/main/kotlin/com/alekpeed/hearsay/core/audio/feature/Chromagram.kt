package com.alekpeed.hearsay.core.audio.feature

import com.alekpeed.hearsay.core.audio.dsp.Spectrogram
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Twelve-bin pitch-class energy per frame — the feature chord recognition actually reads.
 *
 * Two details matter more than the rest. Energy is folded octave-wise, because a chord symbol says
 * nothing about register. And each spectral peak is credited to its pitch class *and* damped at its
 * harmonics, because a low C sounds a G two octaves up loudly enough to invent a fifth that is not
 * being played.
 */
class Chromagram(
    val frames: Array<FloatArray>,
    val hopSeconds: Double,
) {
    val frameCount: Int get() = frames.size

    fun timeMsOfFrame(frame: Int): Long = (frame * hopSeconds * 1000).toLong()

    /** Averages the frames covering `[startMs, endMs)` into one normalized chroma vector. */
    fun averageBetween(startMs: Long, endMs: Long): FloatArray {
        val first = ((startMs / 1000.0) / hopSeconds).toInt().coerceIn(0, maxOf(0, frameCount - 1))
        val last = ((endMs / 1000.0) / hopSeconds).toInt().coerceIn(first, maxOf(0, frameCount - 1))
        val out = FloatArray(PitchClasses)
        var counted = 0
        for (frame in first..last) {
            val source = frames.getOrNull(frame) ?: continue
            for (pc in 0 until PitchClasses) out[pc] += source[pc]
            counted++
        }
        if (counted > 0) for (pc in 0 until PitchClasses) out[pc] /= counted
        return normalize(out)
    }

    companion object {
        const val PitchClasses = 12

        /** A4 = 440 Hz. Deliberately a constant rather than an assumption buried in a formula. */
        const val ReferenceFrequency = 440.0

        private const val LowestFrequency = 55.0
        private const val HighestFrequency = 2_093.0
        private const val HarmonicCount = 6
        private const val HarmonicDecay = 0.75f

        fun of(
            spectrogram: Spectrogram,
            magnitudes: Array<FloatArray> = spectrogram.frames,
        ): Chromagram {
            val binToPitchClass = IntArray(spectrogram.binCount) { -1 }
            val binWeight = FloatArray(spectrogram.binCount)

            for (bin in 1 until spectrogram.binCount) {
                val frequency = spectrogram.frequencyOfBin(bin)
                if (frequency < LowestFrequency || frequency > HighestFrequency) continue
                val midi = 69.0 + 12.0 * log2(frequency / ReferenceFrequency)
                binToPitchClass[bin] = Math.floorMod(midi.roundToInt(), PitchClasses)
                // Taper toward the edges of the useful band so a bass fundamental and a cymbal
                // wash do not both count as harmony.
                binWeight[bin] = bandWeight(frequency)
            }

            val frames = Array(magnitudes.size) { frameIndex ->
                val spectrum = magnitudes[frameIndex]
                val chroma = FloatArray(PitchClasses)
                for (bin in 1 until spectrogram.binCount) {
                    val pitchClass = binToPitchClass[bin]
                    if (pitchClass < 0) continue
                    val energy = spectrum[bin] * binWeight[bin]
                    if (energy <= 0f) continue
                    chroma[pitchClass] += energy
                    // Subtract the overtones this note would produce.
                    //
                    // This is what stops a chord being read as an extension of itself. A sounding
                    // E has a third partial a twelfth above — a B — and a sounding G has a fifth
                    // partial that is also a B. Left alone, a plain C major triad grows a major
                    // seventh out of its own overtones and gets named Cmaj7. Octave partials are
                    // skipped: they land on the pitch class they came from and cancel nothing.
                    var weight = HarmonicDecay
                    for (harmonic in 2..HarmonicCount) {
                        val semitones = (12.0 * log2(harmonic.toDouble())).roundToInt()
                        weight *= HarmonicDecay
                        if (semitones % PitchClasses == 0) continue
                        val harmonicPc = Math.floorMod(pitchClass + semitones, PitchClasses)
                        chroma[harmonicPc] -= energy * weight * HarmonicSuppression
                    }
                }
                for (pc in 0 until PitchClasses) chroma[pc] = maxOf(0f, chroma[pc])
                normalize(compress(chroma))
            }

            return Chromagram(frames, spectrogram.hopSeconds)
        }

        private const val HarmonicSuppression = 1.15f

        private fun bandWeight(frequency: Double): Float {
            val octavesFromCenter = log2(frequency / 440.0)
            return (2.0.pow(-0.35 * octavesFromCenter * octavesFromCenter)).toFloat()
        }

        /** Log compression: a chord is defined by which notes are present, not how loud they are. */
        private fun compress(chroma: FloatArray): FloatArray =
            FloatArray(chroma.size) { ln(1f + 40f * chroma[it]) }

        internal fun normalize(chroma: FloatArray): FloatArray {
            var sum = 0.0
            for (value in chroma) sum += value.toDouble() * value
            val norm = kotlin.math.sqrt(sum).toFloat()
            if (norm < 1e-6f) return chroma
            return FloatArray(chroma.size) { chroma[it] / norm }
        }
    }
}
