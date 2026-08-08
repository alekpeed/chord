package com.alekpeed.hearsay.tools.analyzer

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded audio, in exactly the form the analyzer wants it. */
class DecodedAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationSeconds: Double get() = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0
}

class DecodeFailure(message: String) : Exception(message)

/**
 * Decodes with ffmpeg, which reads everything and is already on the machine.
 *
 * ffmpeg is asked for mono float samples at the analysis rate directly, so the conversion happens
 * in the decoder rather than here and no full-rate stereo copy is ever created. That matters less
 * on a desktop than on a tablet, but a ten-minute file is still 150 MB of pointless allocation.
 */
object FfmpegDecoder {

    fun decode(file: File, sampleRate: Int): DecodedAudio {
        require(file.isFile) { "No such file: $file" }

        val process = ProcessBuilder(
            "ffmpeg",
            "-v", "error",
            "-i", file.absolutePath,
            "-f", "f32le",
            "-acodec", "pcm_f32le",
            "-ac", "1",
            "-ar", sampleRate.toString(),
            "-",
        ).redirectErrorStream(false).start()

        // Drained on its own thread: ffmpeg blocks writing to stderr once its pipe fills, which
        // would deadlock a decode that produced any warnings at all.
        val errors = StringBuilder()
        val errorReader = Thread {
            process.errorStream.bufferedReader().forEachLine { errors.appendLine(it) }
        }
        errorReader.isDaemon = true
        errorReader.start()

        val bytes = process.inputStream.use { it.readBytes() }
        val status = process.waitFor()
        errorReader.join(TimeoutMs)

        if (status != 0) {
            throw DecodeFailure("ffmpeg failed for ${file.name}: ${errors.toString().trim()}")
        }
        if (bytes.isEmpty()) {
            throw DecodeFailure("ffmpeg produced no audio for ${file.name}")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(buffer.remaining())
        buffer.get(samples)
        return DecodedAudio(samples, sampleRate)
    }

    /** Whether ffmpeg is on the path, checked once so the failure is a sentence rather than a stack. */
    fun isAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
        DataInputStream(process.inputStream).use { it.readBytes() }
        process.waitFor() == 0
    }.getOrDefault(false)

    private const val TimeoutMs = 5_000L
}
