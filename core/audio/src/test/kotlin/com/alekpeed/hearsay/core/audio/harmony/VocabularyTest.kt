package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the recognizer is allowed to say.
 *
 * A template that is almost never right is not free. It sits in the candidate set matching muddy
 * frames, and every one it wins is a row the player cannot check against the recording. Measured on
 * a real track, removing three shapes and the triad color took symbols carrying extensions from
 * half the chart to a third, while leaving the number of chords almost unchanged — the same harmony,
 * named more plainly, rather than less harmony.
 */
class VocabularyTest {

    @Test
    fun `sus2 is not in the vocabulary because it is a sus4 wearing a different root`() {
        // D-E-A is Dsus2 and equally A-D-E, an Asus4. Nothing in the audio can separate the two
        // spellings, so keeping both let the recognizer name a chord a fourth away from the harmony
        // on either side of it — which is exactly what was reported.
        assertTrue(
            "sus2 should be gone: ${ChordTemplates.All.map { it.label }}",
            ChordTemplates.All.none { it.label == "sus2" },
        )
        assertTrue("sus4 must remain", ChordTemplates.All.any { it.label == "sus4" })
    }

    @Test
    fun `augmented and minor-major sevenths are not in the vocabulary`() {
        assertTrue(
            "aug and mMaj7 should be gone: ${ChordTemplates.All.map { it.label }}",
            ChordTemplates.All.none { it.label == "aug" || it.label == "mMaj7" },
        )
    }

    @Test
    fun `the ordinary shapes all survived the cut`() {
        val kept = ChordTemplates.All.map { it.label }.toSet()
        for (label in listOf("", "m", "7", "m7", "maj7", "sus4", "7sus4", "dim", "dim7", "m7b5")) {
            assertTrue("$label must remain in the vocabulary, have $kept", label in kept)
        }
    }

    @Test
    fun `a triad stays a triad even when a scale tone sounds over it`() {
        // A held D over a C major triad used to become Cadd9, and a held F became Cadd11 — a name
        // almost nobody voices, since the natural eleventh sits a semitone from the third it would
        // share the symbol with. Both were the melody being written into the harmony.
        val frames = Array(40) {
            FloatArray(12).also { values ->
                for (pc in listOf(0, 4, 7)) values[pc] = 1f
                values[2] = 0.85f
                values[5] = 0.85f
            }.let(Chromagram::normalize)
        }
        val chord = ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(frames, 0.05),
            beatTimesMs = listOf(0L, 2_000L),
        ).single().chord ?: error("Expected a chord")

        assertEquals(ChordQuality.MAJOR, chord.quality)
        assertEquals(SeventhType.NONE, chord.seventh)
        assertTrue("A triad must not collect additions: $chord", chord.additions.isEmpty())
        assertTrue("A triad must not collect extensions: $chord", chord.extensions.isEmpty())
    }

    @Test
    fun `a seventh chord still takes genuinely persistent color`() {
        // The cut is aimed at triads. On a seventh the upper structure is the point of the name, so
        // a ninth that is really there must still be reported.
        val frames = Array(40) {
            FloatArray(12).also { values ->
                for (pc in listOf(0, 4, 7, 10)) values[pc] = 1f
                values[2] = 0.75f
            }.let(Chromagram::normalize)
        }
        val chord = ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(frames, 0.05),
            beatTimesMs = listOf(0L, 2_000L),
        ).single().chord ?: error("Expected a chord")

        assertEquals(SeventhType.MINOR, chord.seventh)
        assertTrue("A persistent ninth on a seventh chord should survive: $chord", 9 in chord.extensions)
    }
}
