package com.alekpeed.hearsay.core.audio.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 FFT.
 *
 * Written by hand rather than pulled in as a dependency: it runs on every frame of every song, it
 * has to work identically under a JVM test and on a tablet, and the twiddle tables are worth
 * caching per size, which a general-purpose library will not do for us.
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    private val cosTable = FloatArray(size / 2) { cos(2.0 * Math.PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * Math.PI * it / size).toFloat() }
    private val reversed = IntArray(size) { bitReverse(it, Integer.numberOfTrailingZeros(size)) }

    /** Transforms [real] and [imaginary] in place. Both arrays must be [size] long. */
    fun transform(real: FloatArray, imaginary: FloatArray) {
        require(real.size == size && imaginary.size == size) { "Buffers must be $size long" }

        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                real[i] = real[j].also { real[j] = real[i] }
                imaginary[i] = imaginary[j].also { imaginary[j] = imaginary[i] }
            }
        }

        var span = 2
        while (span <= size) {
            val half = span / 2
            val step = size / span
            var start = 0
            while (start < size) {
                var k = 0
                for (i in start until start + half) {
                    val j = i + half
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val tr = real[j] * wr - imaginary[j] * wi
                    val ti = real[j] * wi + imaginary[j] * wr
                    real[j] = real[i] - tr
                    imaginary[j] = imaginary[i] - ti
                    real[i] += tr
                    imaginary[i] += ti
                    k += step
                }
                start += span
            }
            span = span shl 1
        }
    }

    /** Magnitude spectrum of a real signal, length `size / 2 + 1`. */
    fun magnitudeSpectrum(frame: FloatArray, out: FloatArray = FloatArray(size / 2 + 1)): FloatArray {
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        frame.copyInto(real, endIndex = minOf(frame.size, size))
        transform(real, imaginary)
        for (bin in out.indices) {
            val re = real[bin]
            val im = imaginary[bin]
            out[bin] = kotlin.math.sqrt(re * re + im * im)
        }
        return out
    }

    private fun bitReverse(value: Int, bits: Int): Int {
        var result = 0
        var input = value
        repeat(bits) {
            result = result shl 1 or (input and 1)
            input = input shr 1
        }
        return result
    }
}

/** Periodic Hann window, the usual choice for overlapping analysis frames. */
fun hannWindow(size: Int): FloatArray =
    FloatArray(size) { 0.5f * (1f - cos(2.0 * Math.PI * it / size).toFloat()) }
