package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import org.junit.Assert.assertTrue
import org.junit.Test

class ChordRecognizerStabilityTest {

    @Test
    fun `related seventh and ninth colors do not make a steady harmony flicker`() {
        val c7 = ChordTemplates.Candidates.first { it.root == 0 && it.template.label == "7" }
        val c9 = ChordTemplates.Candidates.first { it.root == 0 && it.template.label == "9" }
        val sequence = listOf(c9, c7, c9, c7, c9, c7, c9, c7)

        // Ten frames per analysis span. The upper color alternates strongly while the underlying
        // C dominant harmony stays put, which is exactly where raw shared-note transition bonuses
        // used to reward the decoder for switching labels on every span.
        val framesPerSpan = 10
        val frames = Array(sequence.size * framesPerSpan + 1) { frame ->
            sequence[minOf(frame / framesPerSpan, sequence.lastIndex)].vector.copyOf()
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

        val transitions = recognized.zipWithNext().count { (before, after) -> before.chord != after.chord }
        assertTrue(
            "Color-tone alternation should not create a chord change every span: " +
                recognized.map { it.chord },
            transitions <= 1,
        )
    }
}
