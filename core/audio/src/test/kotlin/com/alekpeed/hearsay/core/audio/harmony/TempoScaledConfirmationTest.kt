package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a chord has to hold before it is believed, measured in beats.
 *
 * Reported from the desktop build: four chords inside one bar of a 56 BPM song — Am, Dsus2, Am,
 * Cadd11 — with the middle readings lasting 670 ms each. A beat at 56 BPM is 1071 ms, so each of
 * those was two thirds of a single beat. Nobody plays four harmonies in two seconds in a ballad.
 *
 * They survived because confirmation was written in absolute milliseconds: 670 ms cleared a 650 ms
 * threshold and a 600 ms sandwich ceiling by a few dozen milliseconds each. Those same constants
 * are 1.3 and 1.2 beats at 120 BPM, which is why they looked reasonable when they were chosen and
 * why the defect only showed up on a slow song.
 *
 * The pair of tests below are the whole point: the same 670 ms reading, identical in every respect
 * except the tempo around it, must be discarded in the ballad and kept in the fast tune.
 */
class TempoScaledConfirmationTest {

    private val hopSeconds = 0.05

    /** 56 BPM, the tempo the defect was reported at. */
    private val balladBeatMs = 1_071L

    /** 180 BPM, where the same duration is two full beats of real harmony. */
    private val fastBeatMs = 333L

    @Test
    fun `a two thirds of a beat reading is not a chord in a 56 BPM ballad`() {
        val recognized = recognizeSpans(
            pitchClasses = listOf(A_MINOR, D_MAJOR, A_MINOR),
            durationsMs = listOf(2_000L, 670L, 2_000L),
            beatMs = balladBeatMs,
        )

        val roots = recognized.mapNotNull { it.chord?.root?.pitchClass }.distinct()
        assertEquals("Two seconds of Am does not contain a passing D: ${recognized.map { it.chord }}", listOf(9), roots)
    }

    @Test
    fun `the same duration is a real chord at 180 BPM`() {
        // Identical milliseconds, two full beats of the surrounding tempo. Discarding this would be
        // the regression this product exists to avoid: smoothing away harmony somebody played.
        val recognized = recognizeSpans(
            pitchClasses = listOf(A_MINOR, D_MAJOR, A_MINOR),
            durationsMs = listOf(2_000L, 670L, 2_000L),
            beatMs = fastBeatMs,
        )

        assertTrue(
            "A two-beat chord must survive: ${recognized.map { it.chord }}",
            recognized.any { it.chord?.root?.pitchClass == 2 },
        )
    }

    @Test
    fun `a bar of a slow song does not fill up with chords`() {
        // The reported shape: four readings inside one 56 BPM bar, none of them a beat long.
        val recognized = recognizeSpans(
            pitchClasses = listOf(A_MINOR, D_MAJOR, A_MINOR, C_MAJOR),
            durationsMs = listOf(680L, 670L, 1_180L, 900L),
            beatMs = balladBeatMs,
        )

        val distinct = recognized.mapNotNull { it.chord }.distinct()
        assertTrue(
            "One bar at 56 BPM should not hold four harmonies: ${recognized.map { it.chord }}",
            distinct.size <= 2,
        )
    }

    private fun recognizeSpans(
        pitchClasses: List<List<Int>>,
        durationsMs: List<Long>,
        beatMs: Long,
    ): List<RecognizedChord> {
        val boundaries = durationsMs.runningFold(0L, Long::plus)
        val frameCount = (boundaries.last() / (hopSeconds * 1_000)).toInt() + 1
        val frames = Array(frameCount) { frame ->
            val timeMs = (frame * hopSeconds * 1_000).toLong()
            val span = boundaries.indexOfLast { it <= timeMs }.coerceAtMost(pitchClasses.lastIndex)
            Chromagram.normalize(FloatArray(12).also { values ->
                for (pc in pitchClasses[span]) values[pc] = 1f
            })
        }
        return ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(frames, hopSeconds),
            beatTimesMs = boundaries,
            // Every boundary insists something changed. Duration is what decides whether it did.
            changeLikelihood = FloatArray(durationsMs.size) { if (it == 0) 0f else 1f },
            beatMs = beatMs,
        )
    }

    private companion object {
        val A_MINOR = listOf(9, 0, 4)
        val C_MAJOR = listOf(0, 4, 7)

        /**
         * D major, deliberately not the reported Dsus2.
         *
         * D-E-A is Dsus2 and it is equally A-D-E, which is Asus4 — the same three pitch classes,
         * so no evidence can separate them and the fixture would be testing spelling rather than
         * duration. (That ambiguity is worth noting on its own: in the reported screenshot the
         * recognizer had Am on both sides and still reached for a name rooted a fourth away.)
         * D major shares no such rotation with A minor, so what survives here is decided by how
         * long it lasts, which is what these tests are about.
         */
        val D_MAJOR = listOf(2, 6, 9)
    }
}
