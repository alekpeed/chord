package com.alekpeed.hearsay.core.model.eartraining

import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExerciseGeneratorTest {

    private fun chart(
        symbols: List<String> = listOf("Cmaj7", "Am7", "Dm7", "G7"),
        confidence: Float = 0.9f,
        confirmed: Boolean = false,
        durationMs: Long = 2000,
    ) = SongChart.of(
        chordEvents = symbols.mapIndexed { index, symbol ->
            ChordEvent(
                id = "e$index",
                startMs = index * durationMs,
                endMs = (index + 1) * durationMs,
                chord = ChordParser.parse(symbol),
                confidence = confidence,
                userConfirmed = confirmed,
            )
        },
        beats = (0 until symbols.size * 4).map {
            BeatEvent(it * (durationMs / 4), it % 4 + 1, it / 4 + 1)
        },
    )

    private fun source(chart: SongChart = chart()) =
        ExerciseGenerator.Source("p1", "Autumn Leaves", chart)

    // ---- eligibility ---------------------------------------------------------------------------

    @Test
    fun `a low-confidence chord is never turned into a question`() {
        val rules = EligibilityRules(minimumConfidence = 0.7f)
        val eligible = rules.eligibleEvents(chart(confidence = 0.4f))

        // Asking about a guess would teach the user the app's mistake.
        assertTrue("Expected nothing eligible, got $eligible", eligible.isEmpty())
    }

    @Test
    fun `a chord the user confirmed is eligible however unsure the analysis was`() {
        val rules = EligibilityRules(minimumConfidence = 0.9f)
        val eligible = rules.eligibleEvents(chart(confidence = 0.1f, confirmed = true))

        assertEquals(4, eligible.size)
    }

    @Test
    fun `a region too short to hear is not eligible`() {
        val rules = EligibilityRules(minimumDurationMs = 900)
        assertTrue(rules.eligibleEvents(chart(durationMs = 400)).isEmpty())
    }

    @Test
    fun `a region held far too long is not eligible either`() {
        val rules = EligibilityRules(maximumDurationMs = 12_000)
        assertTrue(rules.eligibleEvents(chart(durationMs = 30_000)).isEmpty())
    }

    @Test
    fun `a chart with no beat grid yields nothing`() {
        val noBeats = SongChart.of(chordEvents = chart().chordEvents)
        assertTrue(EligibilityRules().eligibleEvents(noBeats).isEmpty())
    }

    @Test
    fun `a region with no chord is never eligible`() {
        val silent = SongChart.of(
            chordEvents = listOf(ChordEvent("e0", 0, 2000, null, confidence = 1f)),
            beats = chart().beats,
        )
        assertTrue(EligibilityRules().eligibleEvents(silent).isEmpty())
    }

    // ---- generation ----------------------------------------------------------------------------

    @Test
    fun `generates the requested number of questions`() {
        val exercises = ExerciseGenerator().generate(
            sources = listOf(source()),
            types = ExerciseType.entries.toSet(),
            count = 6,
            random = Random(7),
        )
        assertEquals(6, exercises.size)
    }

    @Test
    fun `every question traces back to the event it came from`() {
        val exercises = ExerciseGenerator().generate(listOf(source()), setOf(ExerciseType.CHORD_QUALITY), 4, Random(1))
        val eventIds = chart().chordEvents.map { it.id }.toSet()

        assertTrue(exercises.isNotEmpty())
        assertTrue(exercises.all { it.sourceEventId in eventIds })
        assertTrue(exercises.all { it.projectId == "p1" })
    }

    @Test
    fun `the correct answer is always among the options`() {
        val exercises = ExerciseGenerator().generate(
            listOf(source()),
            ExerciseType.entries.toSet(),
            12,
            Random(3),
        )
        assertTrue(exercises.isNotEmpty())
        exercises.forEach { exercise ->
            assertTrue(
                "${exercise.type} offered ${exercise.options} without ${exercise.correctAnswer}",
                exercise.correctAnswer in exercise.options,
            )
        }
    }

    @Test
    fun `options are distinct so no question has two right answers`() {
        val exercises = ExerciseGenerator().generate(
            listOf(source()),
            ExerciseType.entries.toSet(),
            12,
            Random(11),
        )
        exercises.forEach { exercise ->
            assertEquals(
                "${exercise.type} repeated an option: ${exercise.options}",
                exercise.options.size,
                exercise.options.distinct().size,
            )
        }
    }

    @Test
    fun `quality questions name the quality of the source chord`() {
        val exercises = ExerciseGenerator().generate(
            listOf(source(chart(listOf("Dm7", "G7", "Cmaj7", "F#m7b5")))),
            setOf(ExerciseType.CHORD_QUALITY),
            4,
            Random(5),
        )
        val answers = exercises.map { it.correctAnswer }.toSet()
        assertTrue(
            "Expected recognisable quality names, got $answers",
            answers.all { it in setOf("Minor seventh", "Dominant seventh", "Major seventh", "Half-diminished") },
        )
    }

    @Test
    fun `root questions answer with the chord's root`() {
        val exercises = ExerciseGenerator().generate(
            listOf(source(chart(listOf("Bb7", "Eb7", "Ab7", "Db7")))),
            setOf(ExerciseType.CHORD_ROOT),
            4,
            Random(2),
        )
        assertTrue(exercises.all { it.correctAnswer.first() in 'A'..'G' })
    }

    @Test
    fun `a missing-chord question spans the chords either side of the gap`() {
        val exercises = ExerciseGenerator().generate(
            listOf(source()),
            setOf(ExerciseType.MISSING_CHORD),
            3,
            Random(13),
        )
        assertTrue(exercises.isNotEmpty())
        exercises.forEach {
            assertTrue("Excerpt should be longer than one chord", it.excerptEndMs - it.excerptStartMs > 2000)
        }
    }

    @Test
    fun `answers are marked case-insensitively but wrong answers stay wrong`() {
        val exercise = ExerciseGenerator()
            .generate(listOf(source()), setOf(ExerciseType.CHORD_ROOT), 1, Random(4)).single()

        assertTrue(exercise.isCorrect(exercise.correctAnswer.lowercase()))
        assertFalse(exercise.isCorrect("not an answer"))
    }

    @Test
    fun `the same seed regenerates the same session`() {
        val first = ExerciseGenerator().generate(listOf(source()), ExerciseType.entries.toSet(), 6, Random(42))
        val second = ExerciseGenerator().generate(listOf(source()), ExerciseType.entries.toSet(), 6, Random(42))

        assertEquals(first.map { it.id to it.correctAnswer }, second.map { it.id to it.correctAnswer })
    }

    @Test
    fun `a library with nothing eligible produces no session rather than a bad one`() {
        val exercises = ExerciseGenerator().generate(
            listOf(source(chart(confidence = 0.2f))),
            ExerciseType.entries.toSet(),
            10,
            Random(1),
        )
        assertTrue(exercises.isEmpty())
    }

    // ---- scoring -------------------------------------------------------------------------------

    @Test
    fun `a summary reports accuracy per skill`() {
        val exercises = ExerciseGenerator().generate(
            listOf(source()),
            setOf(ExerciseType.CHORD_QUALITY, ExerciseType.CHORD_ROOT),
            4,
            Random(9),
        )
        val attempts = exercises.mapIndexed { index, exercise ->
            ExerciseAttempt(
                exercise = exercise,
                answer = if (index % 2 == 0) exercise.correctAnswer else "wrong",
                correct = index % 2 == 0,
                replayCount = 0,
                listeningMode = ListeningMode.FULL_MIX,
                responseTimeMs = 1000,
            )
        }

        val summary = SessionSummary(attempts)
        assertEquals(attempts.size, summary.total)
        assertEquals(0.5f, summary.accuracy, 0.001f)
        assertTrue(summary.bySkill().isNotEmpty())
    }
}
