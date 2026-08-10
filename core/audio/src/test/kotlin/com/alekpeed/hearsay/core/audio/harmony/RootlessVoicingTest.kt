package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who owns a pitch-class set that several names all fit.
 *
 * Reported from the desktop build: a passage heard as Bm7, with a D in the bass, labeled Gmaj9.
 * B-D-F#-A is Bm7; it is also D6; and it is Gmaj9 with the G taken away. Nothing in the upper
 * harmony separates them, because there is nothing there to separate.
 *
 * What does separate them is that one of those names is rooted on a note nobody played. Rootless
 * voicings are the norm in this repertoire, so the recognizer must not reach for an absent root
 * when a name rooted on a note actually sounding — here the one holding the bottom — fits the
 * same evidence. D is in the bass and B is in the chord, so Bm7/D is answerable against the
 * recording. Gmaj9 asks the player to hear a G that is not there.
 */
class RootlessVoicingTest {

    private val hopSeconds = 0.1

    /** [upper] over a held [bass], with an optional trace of the absent root leaking in. */
    private fun recognize(
        upper: List<Int>,
        bass: Int,
        ghostRoot: Int? = null,
        ghostLevel: Float = 0f,
    ): RecognizedChord {
        // The bass is the strongest single tone but not overwhelmingly so: the chromagram's band
        // weighting deliberately pulls the low end down, so a bass note arrives comparable to the
        // voices above it rather than twice their size.
        val frames = Array(41) {
            FloatArray(12).also { values ->
                for (pc in upper) values[pc] += 0.9f
                values[bass] += 0.35f
                if (ghostRoot != null) values[ghostRoot] += ghostLevel
            }.let(Chromagram::normalize)
        }
        val lowBand = Array(frames.size) { FloatArray(12).also { it[bass] = 1f } }

        return ChordRecognizer(slashChords = true, extensionPenalty = 1f).recognize(
            chroma = Chromagram(frames, hopSeconds),
            beatTimesMs = listOf(0L, 4_000L),
            bassChroma = Chromagram(lowBand, hopSeconds),
        ).single()
    }

    @Test
    fun `a name whose root was never played loses to one whose root was`() {
        // B-D-F#-A over a D. Gmaj9 is these notes minus a G nobody played.
        val chord = recognize(upper = BM7, bass = 2).chord ?: error("Expected a chord")

        assertEquals("The root must be a note in the recording, got $chord", 11, chord.root.pitchClass)
        assertEquals(ChordQuality.MINOR, chord.quality)
        assertEquals(SeventhType.MINOR, chord.seventh)
    }

    @Test
    fun `a faint trace of the absent root does not hand the chord to it`() {
        // Bleed, an overtone, or one passing G. The voicing is still Bm7 over its D.
        val chord = recognize(upper = BM7, bass = 2, ghostRoot = 7, ghostLevel = 0.25f).chord
            ?: error("Expected a chord")

        assertEquals("A ghost root must not win: $chord", 11, chord.root.pitchClass)
        assertEquals(ChordQuality.MINOR, chord.quality)
    }

    @Test
    fun `a genuinely present root still wins its own chord`() {
        // G-B-D-F#-A with G in the bass really is Gmaj9. The rule must not make that unreachable.
        val chord = recognize(upper = listOf(11, 2, 6, 9), bass = 7, ghostRoot = 7, ghostLevel = 0.9f).chord
            ?: error("Expected a chord")

        assertEquals("Expected G, got $chord", 7, chord.root.pitchClass)
        assertEquals(ChordQuality.MAJOR, chord.quality)
        assertEquals(SeventhType.MAJOR, chord.seventh)
    }

    private companion object {
        /** B, D, F#, A. */
        val BM7 = listOf(11, 2, 6, 9)
    }
}
