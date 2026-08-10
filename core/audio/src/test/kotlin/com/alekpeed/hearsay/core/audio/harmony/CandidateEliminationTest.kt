package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The elimination pipeline's contract: generate broadly, eliminate aggressively.
 *
 * Each test here is a way the recognizer used to explain every detected pitch class instead of
 * naming the most defensible harmony. A candidate that fails a required musical condition — an
 * unsupported root, a missing defining tone, a transient seventh, color that does not persist —
 * must be removed from consideration, and the simpler well-supported reading must win.
 */
class CandidateEliminationTest {

    /** A triad with a seventh that appears in a quarter of its frames is a triad. */
    @Test
    fun `a transient seventh does not upgrade a triad`() {
        val frames = Array(40) { frame ->
            vector(C_TRIAD).also { if (frame % 4 == 0) it[10] += 1.0f }.let(Chromagram::normalize)
        }
        val chord = recognizeSingle(frames)

        assertEquals(0, chord.root.pitchClass)
        assertEquals("A transient seventh must leave the triad standing: $chord", SeventhType.NONE, chord.seventh)
    }

    /** A persistent seventh is harmony, not noise, and survives. */
    @Test
    fun `a persistent seventh is kept`() {
        val frames = Array(40) { vector(C7).let(Chromagram::normalize) }
        val chord = recognizeSingle(frames)

        assertEquals(0, chord.root.pitchClass)
        assertEquals(SeventhType.MINOR, chord.seventh)
    }

    /** Dm7 with an occasional passing E stays Dm7; it does not become Dm9. */
    @Test
    fun `occasional color does not enrich a seventh chord`() {
        val frames = Array(40) { frame ->
            vector(DM7).also { if (frame % 5 == 0) it[4] += 1.0f }.let(Chromagram::normalize)
        }
        val chord = recognizeSingle(frames)

        assertEquals(2, chord.root.pitchClass)
        assertEquals(ChordQuality.MINOR, chord.quality)
        assertEquals(SeventhType.MINOR, chord.seventh)
        assertTrue("A fleeting ninth must not decorate the chord: $chord", chord.extensions.isEmpty())
    }

    /** A genuinely voiced, persistent ninth earns its name. */
    @Test
    fun `a persistent ninth survives as Dm9`() {
        val frames = Array(40) { vector(DM7).also { it[4] += 0.75f }.let(Chromagram::normalize) }
        val chord = recognizeSingle(frames)

        assertEquals(2, chord.root.pitchClass)
        assertEquals(ChordQuality.MINOR, chord.quality)
        assertEquals(SeventhType.MINOR, chord.seventh)
        assertTrue("A persistent ninth should be kept: $chord", 9 in chord.extensions)
    }

    /** A persistent altered tension is real harmony: C7 with Db across the whole span is C7b9. */
    @Test
    fun `a persistent flat nine survives as C7b9`() {
        val frames = Array(40) { vector(C7).also { it[1] += 0.70f }.let(Chromagram::normalize) }
        val chord = recognizeSingle(frames)

        assertEquals(0, chord.root.pitchClass)
        assertEquals(SeventhType.MINOR, chord.seventh)
        assertTrue("A persistent b9 should be kept: $chord", Alteration.FLAT_NINE in chord.alterations)
    }

    /**
     * Upper notes that happen to spell another chord cannot claim that chord's root. C-E-G with a
     * whisper of A is C major, and the trace must show Am died at root validation, not by luck.
     */
    @Test
    fun `a weak root false candidate is eliminated and the trace says why`() {
        val reports = mutableListOf<SpanEliminationReport>()
        val trace = object : ChordDecisionTrace {
            override fun onSpan(report: SpanEliminationReport) {
                reports += report
            }
        }
        val frames = Array(40) { vector(C_TRIAD).also { it[9] += 0.12f }.let(Chromagram::normalize) }
        val chord = recognizeSingle(frames, trace)

        assertEquals("The supported root must win: $chord", 0, chord.root.pitchClass)
        assertEquals(ChordQuality.MAJOR, chord.quality)

        val amVerdicts = reports.flatMap { report -> report.verdicts.filter { it.candidateName == "Am" } }
        assertTrue("Expected span verdicts to be traced", amVerdicts.isNotEmpty())
        assertTrue(
            "Am should be eliminated by root validation: $amVerdicts",
            amVerdicts.all { it.eliminated && !it.rootPass },
        )
    }

    /** A short different-root reading wedged inside one harmony collapses back into it. */
    @Test
    fun `a sandwich reading collapses into its surroundings`() {
        val recognized = recognizeSpans(
            pitchClasses = listOf(DM7, F_TRIAD, DM7),
            durationsMs = listOf(800L, 400L, 800L),
            changes = floatArrayOf(0f, 1f, 1f),
        )

        assertTrue(
            "Dm7-F-Dm7 with a 400 ms middle must stay Dm7: ${recognized.map { it.chord }}",
            recognized.all { reading ->
                val chord = reading.chord ?: return@all false
                chord.root.pitchClass == 2 && chord.quality == ChordQuality.MINOR
            },
        )
    }

    /** Same-root color change is not a root change: C becoming C7 confirms quickly and cleanly. */
    @Test
    fun `same root refinement confirms without a root change`() {
        val recognized = recognizeSpans(
            pitchClasses = listOf(C_TRIAD, C7),
            durationsMs = listOf(800L, 700L),
            changes = floatArrayOf(0f, 1f),
        )

        assertTrue("The root must never move: ${recognized.map { it.chord }}", recognized.all {
            it.chord?.root?.pitchClass == 0
        })
        assertEquals("The opening triad carries no seventh", SeventhType.NONE, recognized.first().chord?.seventh)
        assertEquals("The persistent seventh is then kept", SeventhType.MINOR, recognized.last().chord?.seventh)
    }

    /** The enricher's parent comparison is observable: color decisions are traced with numbers. */
    @Test
    fun `color elimination decisions are traced`() {
        val colorDecisions = mutableListOf<String>()
        val trace = object : ChordDecisionTrace {
            override fun onColor(startMs: Long, endMs: Long, decision: String) {
                colorDecisions += decision
            }
        }
        val frames = Array(40) { frame ->
            vector(DM7).also { if (frame % 5 == 0) it[4] += 1.0f }.let(Chromagram::normalize)
        }
        recognizeSingle(frames, trace)

        assertFalse("Expected traced color decisions", colorDecisions.isEmpty())
        assertTrue(
            "The fleeting ninth's elimination should be explained: $colorDecisions",
            colorDecisions.any { "eliminated to parent" in it },
        )
    }

    /** No candidate at all is not a reason to invent one: the gate stands down instead. */
    @Test
    fun `elimination stands down rather than emptying a span`() {
        val reports = mutableListOf<SpanEliminationReport>()
        val trace = object : ChordDecisionTrace {
            override fun onSpan(report: SpanEliminationReport) {
                reports += report
            }
        }
        // Flat, nearly uniform chroma: nothing passes validation, and nothing should be forced.
        val frames = Array(40) { FloatArray(12) { 0.05f }.let(Chromagram::normalize) }
        ChordRecognizer(slashChords = false, extensionPenalty = 1f, trace = trace).recognize(
            chroma = Chromagram(frames, HopSeconds),
            beatTimesMs = listOf(0L, 2_000L),
        )

        assertTrue("Expected trace reports", reports.isNotEmpty())
        assertTrue(
            "With no survivors the gate must not apply: $reports",
            reports.none { report -> report.eliminationApplied && report.verdicts.all { it.eliminated } },
        )
    }

    private fun recognizeSingle(frames: Array<FloatArray>, trace: ChordDecisionTrace? = null) =
        ChordRecognizer(slashChords = false, extensionPenalty = 1f, trace = trace).recognize(
            chroma = Chromagram(frames, HopSeconds),
            beatTimesMs = listOf(0L, 2_000L),
        ).single().chord ?: error("Expected a chord")

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
        val C_TRIAD = listOf(0, 4, 7)
        val C7 = listOf(0, 4, 7, 10)
        val DM7 = listOf(2, 5, 9, 0)
        val F_TRIAD = listOf(5, 9, 0)
    }
}
