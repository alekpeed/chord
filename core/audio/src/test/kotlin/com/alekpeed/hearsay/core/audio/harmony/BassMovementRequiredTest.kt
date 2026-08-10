package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No bass, no chord.
 *
 * Reported from the desktop build: rows appearing where nothing can be heard changing underneath
 * them. Players find a chord change by its bass, so a change nobody can hear the bottom of is a
 * row that cannot be checked against the recording — the worst kind of wrong answer, because it
 * looks exactly like a right one.
 *
 * The rule is one-directional and stays that way. The bass must move for the harmony to move; the
 * bass moving is never on its own a reason to say it did. [BassAndColorStabilityTest] guards the
 * other direction, and both hold at once.
 */
class BassMovementRequiredTest {

    private val hopSeconds = 0.1

    /** Upper harmony over a bass that never leaves [bassPitchClass]. */
    private fun overHeldBass(
        upperPerSpan: List<List<Int>>,
        bassPitchClass: Int,
        framesPerSpan: Int = 10,
    ): List<RecognizedChord> {
        val frameCount = upperPerSpan.size * framesPerSpan + 1
        val upperOf = { frame: Int -> upperPerSpan[minOf(frame / framesPerSpan, upperPerSpan.lastIndex)] }

        val fullBand = Array(frameCount) { frame ->
            FloatArray(12).also { values ->
                for (pc in upperOf(frame)) values[pc] += 0.9f
                values[bassPitchClass] += 1.1f
            }.let(Chromagram::normalize)
        }
        val lowBand = Array(frameCount) { FloatArray(12).also { it[bassPitchClass] = 1f } }

        return ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(fullBand, hopSeconds),
            beatTimesMs = (0..upperPerSpan.size).map { it * 1_000L },
            bassChroma = Chromagram(lowBand, hopSeconds),
            // Every boundary is screaming that something changed. Without a bass move it is still
            // not a chord change, which is the whole point of the rule.
            changeLikelihood = FloatArray(upperPerSpan.size) { if (it == 0) 0f else 1f },
        )
    }

    @Test
    fun `upper harmony moving over a held bass is one chord`() {
        val recognized = overHeldBass(
            upperPerSpan = listOf(C_TRIAD, F_OVER_C, C_TRIAD, G_OVER_C, C_TRIAD, F_OVER_C),
            bassPitchClass = 0,
        )

        val roots = recognized.mapNotNull { it.chord?.root?.pitchClass }.distinct()
        assertEquals("A held bass must yield exactly one root: ${recognized.map { it.chord }}", 1, roots.size)
    }

    @Test
    fun `a bass that does move still permits the change`() {
        // The same upper motion, with the bass following it. This must not be blocked: the rule
        // removes unhearable changes, not real ones.
        val bassLine = listOf(0, 0, 5, 5, 7, 7)
        val framesPerSpan = 10
        val frameCount = bassLine.size * framesPerSpan + 1
        val upper = listOf(C_TRIAD, C_TRIAD, F_TRIAD, F_TRIAD, G_TRIAD, G_TRIAD)
        val spanOf = { frame: Int -> minOf(frame / framesPerSpan, bassLine.lastIndex) }

        val fullBand = Array(frameCount) { frame ->
            FloatArray(12).also { values ->
                for (pc in upper[spanOf(frame)]) values[pc] += 0.9f
                values[bassLine[spanOf(frame)]] += 1.1f
            }.let(Chromagram::normalize)
        }
        val lowBand = Array(frameCount) { frame ->
            FloatArray(12).also { it[bassLine[spanOf(frame)]] = 1f }
        }

        val recognized = ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(fullBand, hopSeconds),
            beatTimesMs = (0..bassLine.size).map { it * 1_000L },
            bassChroma = Chromagram(lowBand, hopSeconds),
            changeLikelihood = FloatArray(bassLine.size) { if (it == 0) 0f else 1f },
        )

        val roots = recognized.mapNotNull { it.chord?.root?.pitchClass }
        assertTrue("Expected the bass-corroborated changes to survive: $roots", roots.distinct().size >= 3)
    }

    @Test
    fun `with no low band at all the rule stands down instead of freezing the chart`() {
        // Bass tracking can be switched off. That must not collapse every recording to its opening
        // chord — an unevaluable rule is not a satisfied one.
        val upper = listOf(C_TRIAD, F_TRIAD, G_TRIAD, C_TRIAD)
        val framesPerSpan = 10
        val frames = Array(upper.size * framesPerSpan + 1) { frame ->
            FloatArray(12).also { values ->
                for (pc in upper[minOf(frame / framesPerSpan, upper.lastIndex)]) values[pc] = 1f
            }.let(Chromagram::normalize)
        }

        val recognized = ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(frames, hopSeconds),
            beatTimesMs = (0..upper.size).map { it * 1_000L },
            bassChroma = null,
            changeLikelihood = FloatArray(upper.size) { if (it == 0) 0f else 1f },
        )

        val roots = recognized.mapNotNull { it.chord?.root?.pitchClass }
        assertTrue("Without a low band the chart must still move: $roots", roots.distinct().size >= 3)
    }

    private companion object {
        val C_TRIAD = listOf(0, 4, 7)
        val F_TRIAD = listOf(5, 9, 0)
        val G_TRIAD = listOf(7, 11, 2)

        /** F and G voiced above a C that never leaves the bottom. */
        val F_OVER_C = listOf(5, 9, 0)
        val G_OVER_C = listOf(7, 11, 2)
    }
}
