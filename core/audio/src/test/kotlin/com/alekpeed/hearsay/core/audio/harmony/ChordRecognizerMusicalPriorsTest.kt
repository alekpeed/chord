package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordRecognizerMusicalPriorsTest {

    @Test
    fun `brief structural sandwich candidate never replaces established chord`() {
        val recognized = recognizeSpans(
            pitchClasses = listOf(C7, AMINOR, C7),
            durationsMs = listOf(800L, 300L, 800L),
            changes = floatArrayOf(0f, 1f, 1f),
        )

        assertTrue("Expected C7-Am-C7 sandwich to remain C7: ${recognized.map { it.chord }}", recognized.all {
            it.chord?.root?.pitchClass == 0 && it.chord.seventh == SeventhType.MINOR
        })
    }

    @Test
    fun `strong upper movement confirms C7 to F7 before normal debounce`() {
        val recognized = recognizeSpans(
            pitchClasses = listOf(C7, F7),
            durationsMs = listOf(500L, 400L),
            changes = floatArrayOf(0f, 1f),
        )

        assertEquals(listOf(0, 5), recognized.map { it.chord?.root?.pitchClass })
        assertTrue(recognized.all { it.chord?.seventh == SeventhType.MINOR })
    }

    @Test
    fun `strong two chords per second passage survives confirmation`() {
        val recognized = recognizeSpans(
            pitchClasses = listOf(C7, F7, G7, C7),
            durationsMs = List(4) { 500L },
            changes = floatArrayOf(0f, 1f, 1f, 1f),
        )

        assertEquals(listOf(0, 5, 7, 0), recognized.map { it.chord?.root?.pitchClass })
    }

    @Test
    fun `D F A C prefers D minor seven over F reinterpretation`() {
        val recognized = recognizeSpans(
            pitchClasses = listOf(listOf(2, 5, 9, 0)),
            durationsMs = listOf(1_200L),
            changes = floatArrayOf(0f),
        )
        val chord = recognized.single().chord ?: error("Expected Dm7")

        assertEquals(2, chord.root.pitchClass)
        assertEquals(ChordQuality.MINOR, chord.quality)
        assertEquals(SeventhType.MINOR, chord.seventh)
    }

    @Test
    fun `isolated upper noise cannot decorate C7 but persistent ninth can`() {
        val frameCount = 40
        val noisyFrames = Array(frameCount) { frame ->
            vector(C7).also { values ->
                when (frame) {
                    5 -> values[1] += 1.2f
                    17 -> values[2] += 1.2f
                    29 -> values[6] += 1.2f
                }
            }.let(Chromagram::normalize)
        }
        val persistentNinthFrames = Array(frameCount) {
            vector(C7).also { it[2] += 0.75f }.let(Chromagram::normalize)
        }
        val recognizer = ChordRecognizer(slashChords = false, extensionPenalty = 1f)

        val noisy = recognizer.recognize(
            chroma = Chromagram(noisyFrames, HopSeconds),
            beatTimesMs = listOf(0L, 2_000L),
        ).single().chord ?: error("Expected C7")
        val persistent = recognizer.recognize(
            chroma = Chromagram(persistentNinthFrames, HopSeconds),
            beatTimesMs = listOf(0L, 2_000L),
        ).single().chord ?: error("Expected C9")

        assertTrue("Transient noise created color: $noisy", noisy.extensions.isEmpty() && noisy.alterations.isEmpty())
        assertTrue("Persistent ninth was not retained: $persistent", 9 in persistent.extensions)
    }

    private fun recognizeSpans(
        pitchClasses: List<List<Int>>,
        durationsMs: List<Long>,
        changes: FloatArray,
    ): List<RecognizedChord> {
        val boundaries = durationsMs.runningFold(0L, Long::plus)
        val frameCount = (boundaries.last() / (HopSeconds * 1_000)).toInt() + 1
        val frames = Array(frameCount) { frame ->
            val timeMs = (frame * HopSeconds * 1_000).toLong()
            val span = boundaries.indexOfLast { it <= timeMs }.coerceAtMost(pitchClasses.lastIndex)
            Chromagram.normalize(vector(pitchClasses[span]))
        }
        return ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(frames, HopSeconds),
            beatTimesMs = boundaries,
            changeLikelihood = changes,
        )
    }

    private fun vector(pitchClasses: List<Int>): FloatArray = FloatArray(12).also { values ->
        for (pitchClass in pitchClasses) values[pitchClass] = 1f
    }

    private companion object {
        const val HopSeconds = 0.05
        val C7 = listOf(0, 4, 7, 10)
        val F7 = listOf(5, 9, 0, 3)
        val G7 = listOf(7, 11, 2, 5)
        val AMINOR = listOf(9, 0, 4)
    }
}
