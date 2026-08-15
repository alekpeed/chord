package com.alekpeed.hearsay.tools.desktop.capture

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.Letter
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt is the label. If what the app asks for and what it accepts disagree, the corpus is
 * wrong in a way nobody can see afterward, so the two are pinned to each other here.
 */
class CurriculumTest {

    private val c = NoteSpelling(Letter.C)

    @Test
    fun `every prompt asks for a bass note the chord actually contains`() {
        for (item in Curriculum.all()) {
            assertTrue(
                "${item.id} wants a bass note outside its own chord",
                item.requiredBassPitchClass() in item.expectedPitchClasses(),
            )
        }
    }

    @Test
    fun `every prompt is answerable — the notes it wants include the notes it names`() {
        for (item in Curriculum.all()) {
            val expected = item.expectedPitchClasses()
            assertTrue("${item.id} expects nothing", expected.isNotEmpty())
            if (item.voicing == Voicing.SHELL || item.voicing == Voicing.OPEN) continue
            val named = item.label.pitchClasses()
            assertTrue(
                "${item.id} drops a chord tone it named: $named vs $expected",
                expected.containsAll(named),
            )
        }
    }

    @Test
    fun `ids are unique so a resumed session cannot skip an item it never recorded`() {
        val ids = Curriculum.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all twelve roots appear, because a recognizer that only knows white keys is useless`() {
        val roots = Curriculum.all().map { it.label.root.pitchClass }.toSet()
        assertEquals((0..11).toSet(), roots)
    }

    @Test
    fun `inversions put each structural tone underneath in turn`() {
        val cmaj7 = Chord(root = c, seventh = SeventhType.MAJOR)
        val expected = listOf(0, 4, 7, 11)
        for (inversion in 0..3) {
            val item = CaptureItem("t$inversion", Block.INVERSIONS, cmaj7, Voicing.CLOSE, inversion)
            assertEquals(expected[inversion], item.requiredBassPitchClass())
        }
    }

    @Test
    fun `a shell voicing drops the fifth and nothing else`() {
        val c7 = Chord(root = c, seventh = SeventhType.MINOR)
        val item = CaptureItem("shell", Block.VOICINGS, c7, Voicing.SHELL)
        assertEquals(setOf(0, 4, 10), item.expectedPitchClasses())
    }

    @Test
    fun `a melody note is required to sound but is not part of the chord`() {
        val melody = Curriculum.all().first { it.block == Block.MELODY }
        val extra = melody.extraIntervals.single()
        val extraPitchClass = Math.floorMod(melody.label.root.pitchClass + extra, 12)

        assertTrue("the melody note has to be played", extraPitchClass in melody.expectedPitchClasses())
        assertTrue("the label must not absorb it", extraPitchClass !in melody.label.pitchClasses())
    }

    @Test
    fun `the ambiguous block really does contain pairs with identical notes`() {
        val pairs = Curriculum.all().filter { it.block == Block.AMBIGUOUS }.chunked(2)
        assertTrue(pairs.isNotEmpty())
        for ((first, second) in pairs.map { it[0] to it[1] }) {
            assertEquals(
                "${first.id} and ${second.id} are only worth recording if the notes match",
                first.label.pitchClasses(),
                second.label.pitchClasses(),
            )
            assertTrue(
                "a pair with the same bass tests nothing",
                first.requiredBassPitchClass() != second.requiredBassPitchClass(),
            )
        }
    }

    @Test
    fun `a power chord asks for two notes, not a third it never claims`() {
        val item = Curriculum.all().first {
            it.label.quality == ChordQuality.POWER && it.block == Block.CORE
        }
        assertEquals(2, item.expectedPitchClasses().size)
    }

    @Test
    fun `the prompt renders a symbol a musician can read`() {
        val item = Curriculum.all().first()
        assertNotNull(item.prompt())
        assertTrue(item.prompt().isNotBlank())
        assertTrue(item.detail().contains("bass around"))
    }
}
