package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tensions a chord family is allowed to take at all.
 *
 * Reported from a real chart: Am7b9. The b9 was persistent in the audio — a melody note held a
 * semitone above the root — so every evidence floor passed, and the recognizer printed a chord
 * that composers avoid so consistently that its appearance is far more likely to be an analysis
 * error than a performance. Persistence tests cannot catch this class of mistake, because the
 * offending tone genuinely persists. Only the vocabulary can: some tensions simply do not belong
 * to some chord families, however clearly the pitch class sounds.
 *
 * Every test here feeds the tension with full persistence — the strongest possible evidence — so
 * what is being tested is the vocabulary, never the floors.
 */
class TensionVocabularyTest {

    private val hopSeconds = 0.05

    /** [tension] held at strong level over [chordTones] for the whole span. */
    private fun recognizeWith(chordTones: List<Int>, tension: Int, level: Float = 0.75f) =
        ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(
                Array(40) {
                    FloatArray(12).also { values ->
                        for (pc in chordTones) values[pc] = 1f
                        values[tension] = level
                    }.let(Chromagram::normalize)
                },
                hopSeconds,
            ),
            beatTimesMs = listOf(0L, 2_000L),
        ).single().chord ?: error("Expected a chord")

    @Test
    fun `a minor seventh chord refuses a flat nine however persistent it is`() {
        // A-C-E-G with a persistent B-flat: the reported Am7b9. The Bb is genuinely there — that
        // is the point. It is somebody singing, not the chord.
        val chord = recognizeWith(chordTones = listOf(9, 0, 4, 7), tension = 10)

        assertEquals(9, chord.root.pitchClass)
        assertEquals(ChordQuality.MINOR, chord.quality)
        assertTrue("m7 must never take b9: $chord", Alteration.FLAT_NINE !in chord.alterations)
    }

    @Test
    fun `a major seventh chord refuses a flat nine`() {
        // C-E-G-B with a persistent Db — the Cmaj13b9 family of misreadings.
        val chord = recognizeWith(chordTones = listOf(0, 4, 7, 11), tension = 1)

        assertTrue("maj7 must never take b9: $chord", Alteration.FLAT_NINE !in chord.alterations)
    }

    @Test
    fun `a dominant seventh still takes its flat nine`() {
        // The other direction: 7b9 is an ordinary altered dominant and must survive the tightening.
        val chord = recognizeWith(chordTones = listOf(0, 4, 7, 10), tension = 1)

        assertEquals(SeventhType.MINOR, chord.seventh)
        assertTrue("7b9 is real and must be kept: $chord", Alteration.FLAT_NINE in chord.alterations)
    }

    @Test
    fun `a natural eleventh lands on a minor seventh but not on a dominant`() {
        // Over a minor third the eleventh is m11, ordinary. Over a major third it is the textbook
        // avoid note — players sharpen it or suspend the third, they do not voice it plain.
        val minor = recognizeWith(chordTones = listOf(2, 5, 9, 0), tension = 7)
        assertTrue("m11 is real and must be kept: $minor", 11 in minor.extensions)

        val dominant = recognizeWith(chordTones = listOf(0, 4, 7, 10), tension = 5)
        assertTrue("A natural 11 over a major third is not a voicing: $dominant", 11 !in dominant.extensions)
    }

    @Test
    fun `the sharp eleventh survives on a major seventh`() {
        // maj7#11 is the lydian color and one of the two places #11 is genuinely voiced.
        val chord = recognizeWith(chordTones = listOf(0, 4, 7, 11), tension = 6)

        assertEquals(SeventhType.MAJOR, chord.seventh)
        assertTrue("maj7#11 is real and must be kept: $chord", Alteration.SHARP_ELEVEN in chord.alterations)
    }

    @Test
    fun `a suspended dominant refuses a sharp nine`() {
        // C-F-G-Bb with a persistent Eb. A sus chord has no major third to alter, so the "#9"
        // pitch class is simply the minor third and C7sus4#9 is an absurd symbol — the honest
        // reading of a strong Eb is Cm7. The tension sits below the recognizer's defining-tone
        // floor so the identity stays suspended; held louder, the Eb rightly flips it to Cm7.
        val chord = recognizeWith(chordTones = listOf(0, 5, 7, 10), tension = 3, level = 0.35f)

        assertEquals("premise: $chord", ChordQuality.SUSPENDED, chord.quality)
        assertTrue("7sus4 has no third to sharpen: $chord", Alteration.SHARP_NINE !in chord.alterations)
    }

    @Test
    fun `a suspended dominant refuses a sharp eleven`() {
        // C-F-G-Bb with a persistent F#, a semitone above the structural suspended fourth — the
        // same avoid-note clash that bans the natural eleventh over a major third.
        val chord = recognizeWith(chordTones = listOf(0, 5, 7, 10), tension = 6)

        assertEquals("premise: $chord", ChordQuality.SUSPENDED, chord.quality)
        assertTrue("#11 clashes with the suspended fourth: $chord", Alteration.SHARP_ELEVEN !in chord.alterations)
    }

    @Test
    fun `a half-diminished chord refuses a natural nine`() {
        // B-D-F-A over a held B bass, with a persistent C#. The bass is what names m7b5 here as
        // in production — without it the upper tones read as the relative D minor triad, so this
        // test feeds the recognizer directly instead of using the bassless helper. On a
        // half-diminished chord the plain scale tone is the flat nine; the natural nine is the
        // exotic locrian-natural-2 color, the same class of misreading as the reported Am7b9.
        val frames = Array(40) {
            FloatArray(12).also { values ->
                for (pc in listOf(11, 2, 5, 9)) values[pc] = 1f
                values[1] = 0.75f
            }.let(Chromagram::normalize)
        }
        val bassFrames = Array(40) {
            FloatArray(12).also { values -> values[11] = 1f }
        }
        val chord = ChordRecognizer(slashChords = false, extensionPenalty = 1f).recognize(
            chroma = Chromagram(frames, hopSeconds),
            beatTimesMs = listOf(0L, 1_000L, 2_000L),
            bassChroma = Chromagram(bassFrames, hopSeconds),
        ).first().chord ?: error("Expected a chord")

        assertEquals("premise: $chord", ChordQuality.DIMINISHED, chord.quality)
        assertEquals("premise: $chord", SeventhType.MINOR, chord.seventh)
        assertTrue("The diminished family takes no ninth: $chord", 9 !in chord.extensions)
    }

    @Test
    fun `thirteenths belong to dominants`() {
        val dominant = recognizeWith(chordTones = listOf(0, 4, 7, 10), tension = 9)
        assertTrue("A dominant 13 must be kept: $dominant", 13 in dominant.extensions)

        val minor = recognizeWith(chordTones = listOf(9, 0, 4, 7), tension = 6)
        assertTrue(
            "m13 on a produced chart is overwhelmingly a misreading: $minor",
            13 !in minor.extensions && Alteration.FLAT_THIRTEEN !in minor.alterations,
        )
    }
}
