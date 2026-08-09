package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordRecognizerStabilityTest {

    @Test
    fun `related seventh and ninth colors do not make a steady harmony flicker`() {
        val c7 = ChordTemplates.Candidates.first { it.root == 0 && it.template.label == "7" }
        val c7Vector = c7.vector.copyOf()
        val c9Vector = c7.vector.copyOf().also { it[2] += 0.85f }.let(Chromagram::normalize)
        val sequence = listOf(c9Vector, c7Vector, c9Vector, c7Vector, c9Vector, c7Vector, c9Vector, c7Vector)

        // Upper D alternates strongly while the underlying C7 identity stays put. Ninths are no
        // longer Viterbi states by design: color is attached after root/quality/seventh identity is
        // stable, so this fixture supplies the color as audio evidence rather than asking for a C9
        // identity state that intentionally no longer exists.
        val framesPerSpan = 10
        val frames = Array(sequence.size * framesPerSpan + 1) { frame ->
            sequence[minOf(frame / framesPerSpan, sequence.lastIndex)].copyOf()
        }
        val chroma = Chromagram(frames, hopSeconds = 0.1)
        val boundaries = (0..sequence.size).map { it * 1_000L }

        val recognized = ChordRecognizer(
            slashChords = false,
            extensionPenalty = 0.93f,
        ).recognize(
            chroma = chroma,
            beatTimesMs = boundaries,
        )

        val chords = recognized.mapNotNull { it.chord }
        val transitions = chords.zipWithNext().count { (before, after) -> before != after }
        assertTrue(
            "Color-tone alternation should not create a chord change every span: $chords",
            transitions <= 1,
        )
        assertTrue(
            "Color-tone alternation must not rewrite the harmonic root: ${chords.map { it.root.pitchClass }}",
            chords.all { it.root.pitchClass == 0 },
        )
    }
}
