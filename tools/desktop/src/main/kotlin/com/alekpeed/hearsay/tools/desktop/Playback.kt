package com.alekpeed.hearsay.tools.desktop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * Plays the decoded audio and reports where it is, which is the whole point of this window.
 *
 * The question this tool exists to answer is whether the highlighted chord moves with the music.
 * That cannot be answered by reading numbers out of a chart — it has to be watched against sound.
 * So position is reported from the line's own frame counter rather than a wall clock: a clock
 * started next to the audio drifts against it, and drift is indistinguishable from the defect
 * being looked for.
 *
 * The mono float samples are the same array the analysis read, so what is heard and what was
 * analyzed cannot disagree.
 */
class Playback(private val samples: FloatArray, private val sampleRate: Int) {

    private val playing = AtomicBoolean(false)
    private val seekTo = AtomicLong(-1)
    private var line: SourceDataLine? = null
    private var thread: Thread? = null

    @Volatile
    private var positionFrames: Long = 0

    val durationMs: Long get() = if (sampleRate > 0) samples.size * 1000L / sampleRate else 0

    val positionMs: Long get() = if (sampleRate > 0) positionFrames * 1000L / sampleRate else 0

    val isPlaying: Boolean get() = playing.get()

    fun toggle() {
        if (playing.get()) pause() else play()
    }

    fun play() {
        if (playing.getAndSet(true)) return
        val format = AudioFormat(sampleRate.toFloat(), BitsPerSample, 1, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val opened = AudioSystem.getLine(info) as SourceDataLine
        opened.open(format, BufferBytes)
        opened.start()
        line = opened

        thread = Thread {
            val buffer = ByteArray(ChunkFrames * 2)
            var cursor = positionFrames.toInt().coerceIn(0, samples.size)
            while (playing.get() && cursor < samples.size) {
                val requested = seekTo.getAndSet(-1)
                if (requested >= 0) {
                    cursor = (requested * sampleRate / 1000).toInt().coerceIn(0, samples.size)
                    opened.flush()
                }
                val count = minOf(ChunkFrames, samples.size - cursor)
                for (i in 0 until count) {
                    // Sixteen bit, little endian, which every platform's default mixer accepts.
                    val value = (samples[cursor + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    buffer[i * 2] = (value and 0xFF).toByte()
                    buffer[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                opened.write(buffer, 0, count * 2)
                cursor += count
                // What has left the line, not what has been handed to it: the difference is the
                // buffer, and the buffer is exactly the lag that would look like a late highlight.
                positionFrames = (cursor - framesBuffered(opened)).toLong().coerceAtLeast(0)
            }
            if (cursor >= samples.size) {
                opened.drain()
                positionFrames = samples.size.toLong()
                playing.set(false)
            }
            opened.stop()
            opened.close()
            line = null
        }.apply {
            isDaemon = true
            name = "hearsay-playback"
            start()
        }
    }

    fun pause() {
        playing.set(false)
        thread?.join(JoinTimeoutMs)
        thread = null
    }

    fun seek(ms: Long) {
        val clamped = ms.coerceIn(0, durationMs)
        if (playing.get()) {
            seekTo.set(clamped)
        } else {
            positionFrames = clamped * sampleRate / 1000
        }
    }

    fun stop() {
        pause()
        line?.let {
            it.stop()
            it.close()
        }
        line = null
    }

    /** How many frames are written but not yet audible, so the reported position is what is heard. */
    private fun framesBuffered(line: SourceDataLine): Int =
        ((line.bufferSize - line.available()) / 2).coerceAtLeast(0)

    private companion object {
        const val BitsPerSample = 16
        const val ChunkFrames = 2048

        /** Small enough that a seek is heard promptly, large enough not to underrun. */
        const val BufferBytes = 16384
        const val JoinTimeoutMs = 500L
    }
}
