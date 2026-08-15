package com.alekpeed.hearsay.tools.desktop.capture

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.Letter
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * What the app will and will not accept as an answer.
 *
 * Strictness here is the difference between a corpus and a pile of audio: an accepted take becomes
 * ground truth, so a nearly-right chord that slips through is a mislabeled example that nothing
 * downstream can detect.
 */
class VerifierTest {

    private val c = NoteSpelling(Letter.C)
    private val cmaj7 = Chord(root = c, seventh = SeventhType.MAJOR)

    private fun item(inversion: Int = 0, octave: Int = 3, voicing: Voicing = Voicing.CLOSE) =
        CaptureItem("t", Block.INVERSIONS, cmaj7, voicing, inversion, octave)

    private fun attempt(vararg pitches: Int) =
        ChordAttempt(pitches.sorted().map { PlayedNote(it, 80, 0, 2000) })

    @Test
    fun `the right notes in the right place are accepted`() {
        // C4 E4 G4 B4.
        assertEquals(Verdict.Accepted, Verifier.verify(item(), attempt(60, 64, 67, 71)))
    }

    @Test
    fun `a missing chord tone is named, not waved through`() {
        val verdict = Verifier.verify(item(), attempt(60, 64, 67))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reason.contains("B"))
    }

    @Test
    fun `an extra note is rejected — that would be a different chord`() {
        val verdict = Verifier.verify(item(), attempt(60, 64, 67, 71, 62))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reason.contains("Extra"))
    }

    @Test
    fun `the right notes with the wrong one underneath is a different chord and is refused`() {
        // First inversion asked for, root position played.
        val verdict = Verifier.verify(item(inversion = 1), attempt(60, 64, 67, 71))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reason.contains("lowest"))
    }

    @Test
    fun `first inversion is accepted when the third really is underneath`() {
        // E4 G4 B4 C5.
        assertEquals(Verdict.Accepted, Verifier.verify(item(inversion = 1), attempt(64, 67, 71, 72)))
    }

    @Test
    fun `an octave either side of the asked-for register is allowed`() {
        // C2, C3 and C4 against a request for octave 3.
        assertEquals(Verdict.Accepted, Verifier.verify(item(octave = 3), attempt(36, 40, 43, 47)))
        assertEquals(Verdict.Accepted, Verifier.verify(item(octave = 3), attempt(48, 52, 55, 59)))
        assertEquals(Verdict.Accepted, Verifier.verify(item(octave = 3), attempt(60, 64, 67, 71)))
    }

    @Test
    fun `two octaves away is refused, with the direction to move`() {
        val verdict = Verifier.verify(item(octave = 3), attempt(84, 88, 91, 95))
        assertTrue(verdict is Verdict.Rejected)
        assertEquals("Play it lower.", (verdict as Verdict.Rejected).reason)
    }

    @Test
    fun `a chord rolled one note at a time counts as one gesture`() {
        // Nobody strikes four keys on the same millisecond, and requiring it would reject playing
        // that is entirely correct.
        val detector = GestureDetector()
        detector.noteOn(60, 80, 0)
        detector.noteOn(64, 80, 100)
        detector.noteOn(67, 80, 200)
        detector.noteOn(71, 80, 300)

        assertNull("still holding keys", detector.noteOff(60, 600))
        assertNull("still holding keys", detector.noteOff(64, 650))
        assertNull("still holding keys", detector.noteOff(67, 700))
        val attempt = detector.noteOff(71, 800)

        assertEquals(setOf(0, 4, 7, 11), attempt?.pitchClasses)
        assertEquals(0L, attempt?.startMs)
        assertEquals(800L, attempt?.endMs)
    }

    @Test
    fun `a note-on at zero velocity is a release, because instruments disagree about which to send`() {
        val detector = GestureDetector()
        detector.noteOn(60, 80, 0)
        val attempt = detector.let { it.noteOn(60, 0, 500); it.held() }
        assertTrue("the key must be up", attempt.isEmpty())
    }

    @Test
    fun `an accepted take is written and a rejected one is not`() {
        val file = Files.createTempDirectory("capture").resolve("takes.jsonl")
        val store = CaptureStore(file)
        val session = CaptureSession(listOf(item()), store)

        assertTrue(session.submit(attempt(60, 64, 67)) is Verdict.Rejected)
        assertEquals(0, store.readAll().size)
        assertEquals(0, session.done)

        assertEquals(Verdict.Accepted, session.submit(attempt(60, 64, 67, 71)))
        assertEquals(1, store.readAll().size)
        assertEquals(1, session.done)
        assertTrue(session.finished)
    }

    @Test
    fun `a resumed session does not ask again for what it already has`() {
        val file = Files.createTempDirectory("capture").resolve("takes.jsonl")
        val store = CaptureStore(file)
        val items = listOf(item(), item().copy(id = "second"))

        CaptureSession(items, store).submit(attempt(60, 64, 67, 71))

        val resumed = CaptureSession(items, CaptureStore(file))
        assertEquals(1, resumed.done)
        assertEquals("second", resumed.current?.id)
    }

    @Test
    fun `a stored take keeps the notes, so audio can be rendered from it later`() {
        val file = Files.createTempDirectory("capture").resolve("takes.jsonl")
        val store = CaptureStore(file)
        store.append(item(), attempt(60, 64, 67, 71))

        val capture = store.readAll().single()
        assertEquals(listOf(60, 64, 67, 71), capture.notes.map { it.pitch })
        assertEquals(cmaj7, capture.chord)
    }
}
