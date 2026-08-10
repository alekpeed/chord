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

    /**
     * Folds spectra into chroma one frame at a time.
     *
     * A chroma frame is twelve floats against a spectrum's thousands, so the whole chromagram
     * costs about as much as a single spectrogram frame per second of audio. Accumulating it
     * this way is what lets the separation upstream hand over a reused scratch buffer instead
     * of materializing every frame it produces.
     */
    class Builder(
        private val binCount: Int,
        private val sampleRate: Int,
        private val fftSize: Int,
        private val hopSeconds: Double,
        expectedFrames: Int = 0,
    ) {
        constructor(spectrogram: Spectrogram, expectedFrames: Int = 0) : this(
            binCount = spectrogram.binCount,
            sampleRate = spectrogram.sampleRate,
            fftSize = spectrogram.fftSize,
            hopSeconds = spectrogram.hopSeconds,
            expectedFrames = expectedFrames,
        )

        private val binToPitchClass = IntArray(binCount) { -1 }
        private val binWeight = FloatArray(binCount)
        private val frames = ArrayList<FloatArray>(maxOf(0, expectedFrames))

        init {
            for (bin in 1 until binCount) {
                val frequency = bin.toDouble() * sampleRate / fftSize
                if (frequency < LowestFrequency || frequency > HighestFrequency) continue
                val midi = 69.0 + 12.0 * log2(frequency / ReferenceFrequency)
                binToPitchClass[bin] = Math.floorMod(midi.roundToInt(), PitchClasses)
                // Taper toward the edges of the useful band so a bass fundamental and a cymbal
                // wash do not both count as harmony.
                binWeight[bin] = bandWeight(frequency)
            }
        }

        /** The spectrum is read, never retained; the caller may reuse the array. */
        fun add(spectrum: FloatArray) {
            val chroma = FloatArray(PitchClasses)
            for (bin in 1 until binCount) {
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
            frames += normalize(compress(chroma))
        }

        fun build(): Chromagram = Chromagram(frames.toTypedArray(), hopSeconds)
    }

    companion object {
        const val PitchClasses = 12

        /** A4 = 440 Hz. Deliberately a constant rather than an assumption buried in a formula. */
        const val ReferenceFrequency = 440.0

        private const val LowestFrequency = 55.0

        /**
         * C6, the top of the register accompaniment is actually voiced in.
         *
         * The band used to run to C7. Nothing harmonic lives up there — what does live there is
         * the upper half of the sung melody, plus cymbal wash and the high partials of everything
         * else, and all of it was being folded into the chord evidence as though it were harmony.
         */
        private const val HighestFrequency = 1_046.5

        private const val HarmonicCount = 6
        private const val HarmonicDecay = 0.75f

        fun of(
            spectrogram: Spectrogram,
            magnitudes: Array<FloatArray> = spectrogram.frames,
        ): Chromagram {
            val builder = Builder(spectrogram, magnitudes.size)
            for (frame in magnitudes) builder.add(frame)
            return builder.build()
        }

        private const val HarmonicSuppression = 1.15f

        /**
         * D4, in the register chords are played in — not A4, the register they are sung over.
         *
         * The taper used to be centered on 440 Hz, the heart of the vocal range, which weighted a
         * sung A4 about 2.6 times an A2 in the bass. There is no vocal separation anywhere in this
         * pipeline — the harmonic/percussive split removes drums, and a voice is harmonic — so a
         * held melody note arrives as chord evidence, and being held, it also satisfies every
         * persistence check downstream. A singer sustaining a B-flat over a G and a D is enough to
         * license G minor in a recording containing no B-flat, which is what was reported.
         *
         * Centered here that advantage falls to about 1.5, and above roughly E5 the bass outweighs
         * the melody outright. The voice is still measured and can still be wrong; it no longer
         * starts the argument ahead of the instruments playing the chords.
         *
         * A3 was tried first, to equalize the two octaves completely, and had to be abandoned. At
         * 22 kHz with a 4096-point transform the bins are 5.4 Hz apart while a semitone at C3 is
         * 7.7 Hz, so low fundamentals barely resolve and smear into the neighboring pitch class.
         * Weighting that region hard enough amplified the smear into a phantom minor ninth above
         * every root, and a progression of clean sevenths came back as Dm7b9, G7b9, C7b9. Fixing
         * that properly means resolving low bins better, not weighting them more.
         */
        private const val BandCenterFrequency = 293.66

        private fun bandWeight(frequency: Double): Float {
            val octavesFromCenter = log2(frequency / BandCenterFrequency)
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
