package com.alekpeed.hearsay.core.audio.structure

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DetectedSection(
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val orderIndex: Int,
    val confidence: Float,
    /** Sections sharing a group are the same music heard again. */
    val repetitionGroup: Int,
)

/**
 * Form, from where the music stops resembling itself.
 *
 * A self-similarity matrix over beat-synchronous chroma has bright blocks where material repeats.
 * Correlating a checkerboard kernel down its diagonal produces a novelty curve that peaks at the
 * boundaries between those blocks — the standard approach, and it needs no training data.
 *
 * Sections that turn out to be similar are then grouped, so a chorus heard three times is labelled
 * as the same chorus rather than as three unrelated parts.
 */
object SectionDetector {

    private const val MinimumSectionBeats = 8
    private const val KernelSize = 16
    private const val RepetitionThreshold = 0.86f

    fun detect(
        chroma: Chromagram,
        beatTimesMs: List<Long>,
        beatsPerMeasure: Int,
    ): List<DetectedSection> {
        if (beatTimesMs.size < MinimumSectionBeats * 2) return emptyList()

        val vectors = beatTimesMs.zipWithNext().map { (start, end) -> chroma.averageBetween(start, end) }
        if (vectors.size < KernelSize * 2) return emptyList()

        val novelty = noveltyCurve(vectors)
        val boundaries = pickBoundaries(novelty, beatsPerMeasure)

        val bounded = (listOf(0) + boundaries + listOf(vectors.size)).distinct().sorted()
        val rawSections = bounded.zipWithNext().filter { (start, end) -> end - start >= MinimumSectionBeats }
        if (rawSections.isEmpty()) return emptyList()

        val profiles = rawSections.map { (start, end) -> averageOf(vectors, start, end) }
        val groups = groupRepetitions(profiles)
        val labels = labelGroups(groups, rawSections)

        return rawSections.mapIndexed { index, (start, end) ->
            DetectedSection(
                label = labels[index],
                startMs = beatTimesMs[start],
                endMs = beatTimesMs[min(end, beatTimesMs.size - 1)],
                orderIndex = index,
                confidence = 0.5f,
                repetitionGroup = groups[index],
            )
        }
    }

    /** Checkerboard-kernel correlation along the diagonal of the self-similarity matrix. */
    private fun noveltyCurve(vectors: List<FloatArray>): FloatArray {
        val size = vectors.size
        val novelty = FloatArray(size)
        val half = KernelSize / 2

        for (centre in half until size - half) {
            var pastToPast = 0f
            var futureToFuture = 0f
            var pastToFuture = 0f
            for (i in 0 until half) {
                for (j in 0 until half) {
                    pastToPast += similarity(vectors[centre - half + i], vectors[centre - half + j])
                    futureToFuture += similarity(vectors[centre + i], vectors[centre + j])
                    pastToFuture += similarity(vectors[centre - half + i], vectors[centre + j])
                }
            }
            val within = (pastToPast + futureToFuture) / 2f
            novelty[centre] = max(0f, (within - pastToFuture) / (half * half))
        }
        return novelty
    }

    private fun pickBoundaries(novelty: FloatArray, beatsPerMeasure: Int): List<Int> {
        var mean = 0f
        for (value in novelty) mean += value
        mean /= max(1, novelty.size)
        var variance = 0f
        for (value in novelty) variance += (value - mean) * (value - mean)
        val deviation = kotlin.math.sqrt(variance / max(1, novelty.size))
        val threshold = mean + deviation

        val peaks = mutableListOf<Int>()
        for (i in 1 until novelty.size - 1) {
            if (novelty[i] <= threshold) continue
            if (novelty[i] < novelty[i - 1] || novelty[i] < novelty[i + 1]) continue
            if (peaks.isNotEmpty() && i - peaks.last() < MinimumSectionBeats) {
                if (novelty[i] > novelty[peaks.last()]) peaks[peaks.lastIndex] = i
                continue
            }
            peaks += i
        }

        // Sections start on bar lines. Snapping avoids a boundary landing mid-bar and making every
        // downstream measure number look off by a beat.
        return peaks.map { peak ->
            val remainder = peak % beatsPerMeasure
            if (remainder <= beatsPerMeasure / 2) peak - remainder else peak + (beatsPerMeasure - remainder)
        }.filter { it > 0 }.distinct()
    }

    private fun averageOf(vectors: List<FloatArray>, start: Int, end: Int): FloatArray {
        val out = FloatArray(12)
        for (i in start until end) for (pc in 0 until 12) out[pc] += vectors[i][pc]
        val count = max(1, end - start)
        for (pc in 0 until 12) out[pc] /= count
        return Chromagram.normalize(out)
    }

    private fun groupRepetitions(profiles: List<FloatArray>): IntArray {
        val groups = IntArray(profiles.size) { -1 }
        var next = 0
        for (i in profiles.indices) {
            if (groups[i] != -1) continue
            groups[i] = next
            for (j in i + 1 until profiles.size) {
                if (groups[j] != -1) continue
                if (similarity(profiles[i], profiles[j]) >= RepetitionThreshold) groups[j] = next
            }
            next++
        }
        return groups
    }

    /**
     * Names by position and repetition rather than by guessing musical function.
     *
     * Calling something a "chorus" would be a claim about the music this analysis cannot support,
     * so sections are Section A, B, C by first appearance, and a repeat is named for what it repeats.
     */
    private fun labelGroups(groups: IntArray, sections: List<Pair<Int, Int>>): List<String> {
        val letters = mutableMapOf<Int, String>()
        val seen = mutableMapOf<Int, Int>()
        return sections.indices.map { index ->
            val group = groups[index]
            val letter = letters.getOrPut(group) { ('A' + letters.size).toString() }
            val occurrence = seen.merge(group, 1, Int::plus)!!
            if (occurrence == 1) "Section $letter" else "Section $letter ($occurrence)"
        }
    }

    private fun similarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}

/**
 * Monophonic pitch tracking for a bass line, by autocorrelation over a low-passed band.
 *
 * Bass is the one part where a single pitch per moment is a safe assumption, and knowing it is what
 * lets an inversion be named rather than guessed. Anything polyphonic is left alone.
 */
object BassTracker {

    private const val MinBassHz = 38.0
    private const val MaxBassHz = 400.0

    data class BassNote(val startMs: Long, val endMs: Long, val midiPitch: Int, val confidence: Float)

    fun track(
        samples: FloatArray,
        sampleRate: Int,
        beatTimesMs: List<Long>,
    ): List<BassNote> {
        if (beatTimesMs.size < 2) return emptyList()
        val minLag = (sampleRate / MaxBassHz).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / MinBassHz).toInt()

        return beatTimesMs.zipWithNext().mapNotNull { (start, end) ->
            val from = ((start / 1000.0) * sampleRate).toInt()
            val to = ((end / 1000.0) * sampleRate).toInt().coerceAtMost(samples.size)
            if (to - from < maxLag * 2) return@mapNotNull null

            val window = samples.copyOfRange(from.coerceAtLeast(0), to)
            val (lag, clarity) = autocorrelationPitch(window, minLag, maxLag) ?: return@mapNotNull null
            if (clarity < 0.3f) return@mapNotNull null

            val frequency = sampleRate.toDouble() / lag
            val midi = (69.0 + 12.0 * kotlin.math.log2(frequency / 440.0)).toInt()
            BassNote(start, end, midi, clarity)
        }
    }

    private fun autocorrelationPitch(window: FloatArray, minLag: Int, maxLag: Int): Pair<Int, Float>? {
        var energy = 0f
        for (sample in window) energy += sample * sample
        if (energy < 1e-6f) return null

        var bestLag = -1
        var bestValue = 0f
        for (lag in minLag..min(maxLag, window.size / 2)) {
            var sum = 0f
            for (i in 0 until window.size - lag) sum += window[i] * window[i + lag]
            val normalized = sum / energy
            if (normalized > bestValue) {
                bestValue = normalized
                bestLag = lag
            }
        }
        return if (bestLag > 0) bestLag to bestValue.coerceIn(0f, 1f) else null
    }

    /** Simple one-pole low-pass, enough to isolate the band the bass lives in. */
    fun lowPass(samples: FloatArray, sampleRate: Int, cutoffHz: Double = 500.0): FloatArray {
        val rc = 1.0 / (2 * Math.PI * cutoffHz)
        val dt = 1.0 / sampleRate
        val alpha = (dt / (rc + dt)).toFloat()
        val out = FloatArray(samples.size)
        var previous = 0f
        for (i in samples.indices) {
            previous += alpha * (samples[i] - previous)
            out[i] = previous
        }
        return out
    }
}

internal fun approximately(a: Float, b: Float, tolerance: Float = 1e-4f): Boolean = abs(a - b) < tolerance
