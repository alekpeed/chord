package com.alekpeed.hearsay.core.audio.dsp

/**
 * Short-time Fourier magnitudes.
 *
 * Every later stage reads from this one structure — onsets, chroma, harmonic/percussive separation
 * and structure all derive from the same frames, so the expensive part happens once.
 */
class Spectrogram(
    val frames: Array<FloatArray>,
    val sampleRate: Int,
    val fftSize: Int,
    val hopSize: Int,
) {
    val frameCount: Int get() = frames.size
    val binCount: Int get() = fftSize / 2 + 1

    /** Seconds covered by one hop; the time resolution of everything downstream. */
    val hopSeconds: Double get() = hopSize.toDouble() / sampleRate

    fun timeMsOfFrame(frame: Int): Long = (frame * hopSeconds * 1000).toLong()

    fun frameOfTimeMs(timeMs: Long): Int =
        ((timeMs / 1000.0) / hopSeconds).toInt().coerceIn(0, maxOf(0, frameCount - 1))

    fun frequencyOfBin(bin: Int): Double = bin.toDouble() * sampleRate / fftSize

    companion object {
        const val DefaultFftSize = 4096
        const val DefaultHopSize = 512

        fun of(
            buffer: AudioBuffer,
            fftSize: Int = DefaultFftSize,
            hopSize: Int = DefaultHopSize,
        ): Spectrogram {
            val frames = ArrayList<FloatArray>(frameCountFor(buffer, hopSize))
            forEachFrame(buffer, fftSize, hopSize) { _, magnitudes -> frames += magnitudes }
            return Spectrogram(frames.toTypedArray(), buffer.sampleRate, fftSize, hopSize)
        }

        /**
         * Transforms one frame at a time without keeping any of them.
         *
         * For a consumer that only reduces each frame — chroma, flux — this is the difference
         * between a few kilobytes and a spectrogram, which on a long recording is hundreds of
         * megabytes. Each frame handed over is freshly allocated and may be retained.
         */
        fun forEachFrame(
            buffer: AudioBuffer,
            fftSize: Int = DefaultFftSize,
            hopSize: Int = DefaultHopSize,
            onFrame: (index: Int, magnitudes: FloatArray) -> Unit,
        ) {
            val fft = Fft(fftSize)
            val window = hannWindow(fftSize)
            val half = fftSize / 2
            for (frameIndex in 0 until frameCountFor(buffer, hopSize)) {
                // Centered on the time the frame stands for, not beginning there.
                //
                // A window that begins at its own timestamp reports everything early: a drum hit is
                // first seen when it enters the far end of the window, which for a 4096-sample
                // window is 186 ms before it happens. Every beat came out roughly 140 ms ahead of
                // the music, on every recording, at every tempo — which a listener hears as the
                // chart running ahead of what they are playing along to.
                val frame = buffer.frameAt(frameIndex * hopSize - half, fftSize)
                for (i in frame.indices) frame[i] *= window[i]
                onFrame(frameIndex, fft.magnitudeSpectrum(frame))
            }
        }

        /**
         * How many frames a buffer yields.
         *
         * Independent of the transform size now that windows are centered: a frame exists for every
         * hop across the whole recording, with the ends zero-padded, rather than only where a full
         * window fits inside the audio.
         */
        fun frameCountFor(buffer: AudioBuffer, hopSize: Int): Int =
            maxOf(1, buffer.samples.size / hopSize + 1)

        fun binCountFor(fftSize: Int): Int = fftSize / 2 + 1
    }
}

/**
 * Median-filtering harmonic/percussive source separation.
 *
 * Sustained pitches form horizontal ridges across time; drum hits form vertical ridges across
 * frequency. Median-filtering the magnitudes along each axis and comparing the two gives a soft
 * mask that pulls them apart. It is a fraction of the cost of a neural separator and it is exactly
 * the preprocessing chord detection wants: percussive energy removed, sustained harmony kept.
 */
object HarmonicPercussive {

    data class Split(val harmonic: Array<FloatArray>, val percussive: Array<FloatArray>)

    /**
     * Separates one frame at a time, handing each pair to [onFrame] instead of building the result.
     *
     * Both median filters read only from the source spectrogram — the time filter looks a fixed
     * number of frames either side of the one being produced, and the frequency filter never leaves
     * its own frame — so nothing has to be accumulated to compute a later frame. That makes the
     * whole separation a scratch pair of arrays rather than four more copies of the spectrogram.
     *
     * The four copies were not a subtlety. On a seven-minute recording each one is around 150 MB,
     * and allocating them is what made the analysis die of memory exhaustion on a real tablet.
     *
     * The frames passed to [onFrame] are reused between calls. A consumer that needs to keep one
     * must copy it.
     */
    fun separateInto(
        spectrogram: Spectrogram,
        timeFilterLength: Int = DefaultFilterLength,
        frequencyFilterLength: Int = DefaultFilterLength,
        onFrame: (index: Int, harmonic: FloatArray, percussive: FloatArray) -> Unit,
    ) {
        val frames = spectrogram.frames
        val frameCount = frames.size
        val bins = spectrogram.binCount

        val harmonicFrame = FloatArray(bins)
        val percussiveFrame = FloatArray(bins)
        val timeSlice = FloatArray(timeFilterLength)
        val freqSlice = FloatArray(frequencyFilterLength)
        val timeRadius = timeFilterLength / 2
        val frequencyRadius = frequencyFilterLength / 2

        for (frame in 0 until frameCount) {
            val source = frames[frame]
            for (bin in 0 until bins) {
                // Horizontal median: smooths along time, so sustained partials survive.
                var timeCount = 0
                for (offset in -timeRadius..timeRadius) {
                    val index = frame + offset
                    if (index in 0 until frameCount) timeSlice[timeCount++] = frames[index][bin]
                }
                val h = median(timeSlice, timeCount)

                // Vertical median: smooths across frequency, so broadband transients survive.
                var freqCount = 0
                for (offset in -frequencyRadius..frequencyRadius) {
                    val index = bin + offset
                    if (index in 0 until bins) freqSlice[freqCount++] = source[index]
                }
                val p = median(freqSlice, freqCount)

                val total = h + p + 1e-9f
                harmonicFrame[bin] = source[bin] * (h / total)
                percussiveFrame[bin] = source[bin] * (p / total)
            }
            onFrame(frame, harmonicFrame, percussiveFrame)
        }
    }

    /**
     * Materializes the whole separation.
     *
     * Kept for tests and for callers small enough not to care; the pipeline itself uses
     * [separateInto], because this allocates two more copies of the spectrogram.
     */
    fun separate(
        spectrogram: Spectrogram,
        timeFilterLength: Int = DefaultFilterLength,
        frequencyFilterLength: Int = DefaultFilterLength,
    ): Split {
        val harmonic = Array(spectrogram.frames.size) { FloatArray(spectrogram.binCount) }
        val percussive = Array(spectrogram.frames.size) { FloatArray(spectrogram.binCount) }
        separateInto(spectrogram, timeFilterLength, frequencyFilterLength) { index, h, p ->
            h.copyInto(harmonic[index])
            p.copyInto(percussive[index])
        }
        return Split(harmonic, percussive)
    }

    const val DefaultFilterLength = 17

    private fun median(values: FloatArray, count: Int): Float {
        if (count == 0) return 0f
        val slice = values.copyOf(count)
        slice.sort()
        return if (count % 2 == 1) slice[count / 2] else (slice[count / 2 - 1] + slice[count / 2]) / 2f
    }
}
