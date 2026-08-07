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
            val fft = Fft(fftSize)
            val window = hannWindow(fftSize)
            val frameCount = maxOf(1, (buffer.samples.size - fftSize) / hopSize + 1)
            val frames = Array(frameCount) { frameIndex ->
                val frame = buffer.frameAt(frameIndex * hopSize, fftSize)
                for (i in frame.indices) frame[i] *= window[i]
                fft.magnitudeSpectrum(frame)
            }
            return Spectrogram(frames, buffer.sampleRate, fftSize, hopSize)
        }
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

    fun separate(
        spectrogram: Spectrogram,
        timeFilterLength: Int = 17,
        frequencyFilterLength: Int = 17,
    ): Split {
        val frames = spectrogram.frames
        val frameCount = frames.size
        val bins = spectrogram.binCount

        val harmonicEnhanced = Array(frameCount) { FloatArray(bins) }
        val percussiveEnhanced = Array(frameCount) { FloatArray(bins) }

        // Horizontal median: smooths along time, so sustained partials survive.
        val timeSlice = FloatArray(timeFilterLength)
        for (bin in 0 until bins) {
            for (frame in 0 until frameCount) {
                var count = 0
                for (offset in -(timeFilterLength / 2)..(timeFilterLength / 2)) {
                    val index = frame + offset
                    if (index in 0 until frameCount) timeSlice[count++] = frames[index][bin]
                }
                harmonicEnhanced[frame][bin] = median(timeSlice, count)
            }
        }

        // Vertical median: smooths across frequency, so broadband transients survive.
        val freqSlice = FloatArray(frequencyFilterLength)
        for (frame in 0 until frameCount) {
            for (bin in 0 until bins) {
                var count = 0
                for (offset in -(frequencyFilterLength / 2)..(frequencyFilterLength / 2)) {
                    val index = bin + offset
                    if (index in 0 until bins) freqSlice[count++] = frames[frame][index]
                }
                percussiveEnhanced[frame][bin] = median(freqSlice, count)
            }
        }

        val harmonic = Array(frameCount) { FloatArray(bins) }
        val percussive = Array(frameCount) { FloatArray(bins) }
        for (frame in 0 until frameCount) {
            for (bin in 0 until bins) {
                val h = harmonicEnhanced[frame][bin]
                val p = percussiveEnhanced[frame][bin]
                val total = h + p + 1e-9f
                harmonic[frame][bin] = frames[frame][bin] * (h / total)
                percussive[frame][bin] = frames[frame][bin] * (p / total)
            }
        }
        return Split(harmonic, percussive)
    }

    private fun median(values: FloatArray, count: Int): Float {
        if (count == 0) return 0f
        val slice = values.copyOf(count)
        slice.sort()
        return if (count % 2 == 1) slice[count / 2] else (slice[count / 2 - 1] + slice[count / 2]) / 2f
    }
}
