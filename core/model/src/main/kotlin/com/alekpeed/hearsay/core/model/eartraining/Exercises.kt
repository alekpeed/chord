package com.alekpeed.hearsay.core.model.eartraining

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SeventhType
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlin.random.Random

enum class ExerciseType(val displayName: String, val skill: String) {
    CHORD_QUALITY("Chord quality", "Quality"),
    CHORD_ROOT("Chord root", "Root"),
    BASS_NOTE("Bass note", "Bass"),
    CHORD_CHANGE("Where it changes", "Timing"),
    VOICING_NOTES("Notes in the chord", "Voicing"),
    MISSING_CHORD("Missing chord", "Progression"),
}

/** Whether the excerpt is heard in the mix or on its own. */
enum class ListeningMode { FULL_MIX, ISOLATED }

/**
 * One question, with everything needed to ask it, mark it and prove where it came from.
 *
 * [sourceEventId] is not decoration: a result the user disputes has to be traceable back to the
 * exact chord region it was generated from, so they can go and look at the evidence.
 */
data class Exercise(
    val id: String,
    val projectId: String,
    val projectTitle: String,
    val type: ExerciseType,
    val sourceEventId: String,
    val excerptStartMs: Long,
    val excerptEndMs: Long,
    val prompt: String,
    val options: List<String>,
    val correctAnswer: String,
    val answerChord: Chord?,
    val confidenceAtGeneration: Float,
) {
    fun isCorrect(answer: String): Boolean = answer.trim().equals(correctAnswer, ignoreCase = true)
}

data class ExerciseAttempt(
    val exercise: Exercise,
    val answer: String?,
    val correct: Boolean,
    val replayCount: Int,
    val listeningMode: ListeningMode,
    val responseTimeMs: Long,
)

data class SessionSummary(
    val attempts: List<ExerciseAttempt>,
) {
    val total: Int get() = attempts.size
    val correct: Int get() = attempts.count { it.correct }
    val accuracy: Float get() = if (total == 0) 0f else correct.toFloat() / total

    /** Accuracy per skill, which is what tells a player where to spend the next session. */
    fun bySkill(): Map<String, Float> = attempts
        .groupBy { it.exercise.type.skill }
        .mapValues { (_, group) -> group.count { it.correct }.toFloat() / group.size }
}

/**
 * Which chord events are honest material for a question.
 *
 * The rule this exists to enforce: **never ask a question whose answer the app is not sure of.**
 * A question generated from a low-confidence guess teaches the user the app's mistake. So an event
 * qualifies only if the user confirmed it, or the analysis was confident, and in either case only
 * if it lasts long enough to actually hear.
 */
data class EligibilityRules(
    val minimumConfidence: Float = 0.7f,
    val minimumDurationMs: Long = 900L,
    val maximumDurationMs: Long = 12_000L,
    val requireBeatGrid: Boolean = true,
) {
    fun isEligible(event: ChordEvent, chart: SongChart): Boolean {
        if (event.chord == null) return false
        if (event.durationMs < minimumDurationMs) return false
        if (event.durationMs > maximumDurationMs) return false
        if (requireBeatGrid && chart.beats.isEmpty()) return false
        return event.userConfirmed || event.confidence >= minimumConfidence
    }

    fun eligibleEvents(chart: SongChart): List<ChordEvent> =
        chart.chordEvents.filter { isEligible(it, chart) }
}

/**
 * Builds a session out of a project's own analysis.
 *
 * Deterministic given a seed, so a session can be regenerated exactly — which is what makes a
 * disputed result reviewable rather than a story about something that happened once.
 */
class ExerciseGenerator(
    private val rules: EligibilityRules = EligibilityRules(),
) {

    data class Source(
        val projectId: String,
        val projectTitle: String,
        val chart: SongChart,
    )

    fun generate(
        sources: List<Source>,
        types: Set<ExerciseType>,
        count: Int,
        random: Random = Random.Default,
    ): List<Exercise> {
        if (types.isEmpty() || count <= 0) return emptyList()

        val pool = sources.flatMap { source ->
            rules.eligibleEvents(source.chart).map { source to it }
        }
        if (pool.isEmpty()) return emptyList()

        val shuffled = pool.shuffled(random)
        val typeList = types.toList()
        val exercises = mutableListOf<Exercise>()

        var index = 0
        var guard = 0
        while (exercises.size < count && guard < pool.size * MaxAttemptsPerSlot) {
            val (source, event) = shuffled[index % shuffled.size]
            val type = typeList[exercises.size % typeList.size]
            build(source, event, type, random)?.let(exercises::add)
            index++
            guard++
        }
        return exercises
    }

    private fun build(
        source: Source,
        event: ChordEvent,
        type: ExerciseType,
        random: Random,
    ): Exercise? {
        val chord = event.chord ?: return null
        return when (type) {
            ExerciseType.CHORD_QUALITY -> qualityExercise(source, event, chord, random)
            ExerciseType.CHORD_ROOT -> rootExercise(source, event, chord, random)
            ExerciseType.BASS_NOTE -> bassExercise(source, event, chord, random)
            ExerciseType.CHORD_CHANGE -> changeExercise(source, event)
            ExerciseType.VOICING_NOTES -> voicingExercise(source, event, chord, random)
            ExerciseType.MISSING_CHORD -> missingChordExercise(source, event, chord, random)
        }
    }

    private fun qualityExercise(source: Source, event: ChordEvent, chord: Chord, random: Random): Exercise {
        val answer = qualityLabel(chord)
        val distractors = QualityLabels.filter { it != answer }.shuffled(random).take(3)
        return exercise(
            source, event, chord, ExerciseType.CHORD_QUALITY,
            prompt = "What kind of chord is this?",
            options = (distractors + answer).shuffled(random),
            answer = answer,
        )
    }

    private fun rootExercise(source: Source, event: ChordEvent, chord: Chord, random: Random): Exercise {
        val answer = chord.root.render(unicodeAccidentals = true)
        val distractors = (0 until 12)
            .map { NoteSpelling.fromPitchClass(it, chord.root.alteration < 0).render() }
            .filter { it != answer }
            .shuffled(random)
            .take(3)
        return exercise(
            source, event, chord, ExerciseType.CHORD_ROOT,
            prompt = "What is the root?",
            options = (distractors + answer).shuffled(random),
            answer = answer,
        )
    }

    private fun bassExercise(source: Source, event: ChordEvent, chord: Chord, random: Random): Exercise? {
        val answer = chord.effectiveBass.render()
        val distractors = chord.pitchClasses()
            .map { NoteSpelling.fromPitchClass(it, chord.root.alteration < 0).render() }
            .filter { it != answer }
            .distinct()
            .shuffled(random)
            .take(3)
        if (distractors.isEmpty()) return null
        return exercise(
            source, event, chord, ExerciseType.BASS_NOTE,
            prompt = "Which note is in the bass?",
            options = (distractors + answer).shuffled(random),
            answer = answer,
        )
    }

    /**
     * Plays across a chord boundary and asks where it fell.
     *
     * The excerpt deliberately starts before the change and ends after it, so the answer is a
     * judgement about time rather than about which chord was playing.
     */
    private fun changeExercise(source: Source, event: ChordEvent): Exercise? {
        val index = source.chart.chordEvents.indexOfFirst { it.id == event.id }
        val previous = source.chart.chordEvents.getOrNull(index - 1) ?: return null
        val beatsBefore = source.chart.beats.filter { it.timeMs in previous.startMs until event.startMs }
        if (beatsBefore.size < 2) return null

        val answerBeat = beatsBefore.indexOfFirst { it.timeMs >= event.startMs - BoundaryToleranceMs }
        val answer = "Beat ${if (answerBeat >= 0) answerBeat + 1 else beatsBefore.size}"
        val options = (1..beatsBefore.size).map { "Beat $it" }

        return Exercise(
            id = "${event.id}:${ExerciseType.CHORD_CHANGE.name}",
            projectId = source.projectId,
            projectTitle = source.projectTitle,
            type = ExerciseType.CHORD_CHANGE,
            sourceEventId = event.id,
            excerptStartMs = previous.startMs,
            excerptEndMs = event.endMs,
            prompt = "On which beat does the chord change?",
            options = options,
            correctAnswer = answer,
            answerChord = event.chord,
            confidenceAtGeneration = minOf(previous.confidence, event.confidence),
        )
    }

    private fun voicingExercise(source: Source, event: ChordEvent, chord: Chord, random: Random): Exercise {
        val answer = chord.pitchClasses().size.toString()
        val options = listOf("2", "3", "4", "5").distinct()
        return exercise(
            source, event, chord, ExerciseType.VOICING_NOTES,
            prompt = "How many different notes are sounding?",
            options = options.shuffled(random),
            answer = answer,
        )
    }

    private fun missingChordExercise(source: Source, event: ChordEvent, chord: Chord, random: Random): Exercise? {
        val index = source.chart.chordEvents.indexOfFirst { it.id == event.id }
        val before = source.chart.chordEvents.getOrNull(index - 1) ?: return null
        val after = source.chart.chordEvents.getOrNull(index + 1) ?: return null

        val answer = ChordFormatter.format(chord)
        val distractors = source.chart.chordEvents
            .mapNotNull { it.chord }
            .map { ChordFormatter.format(it) }
            .filter { it != answer }
            .distinct()
            .shuffled(random)
            .take(3)
        if (distractors.size < 2) return null

        return Exercise(
            id = "${event.id}:${ExerciseType.MISSING_CHORD.name}",
            projectId = source.projectId,
            projectTitle = source.projectTitle,
            type = ExerciseType.MISSING_CHORD,
            sourceEventId = event.id,
            excerptStartMs = before.startMs,
            excerptEndMs = after.endMs,
            prompt = "Which chord fills the gap?",
            options = (distractors + answer).shuffled(random),
            correctAnswer = answer,
            answerChord = chord,
            confidenceAtGeneration = event.confidence,
        )
    }

    private fun exercise(
        source: Source,
        event: ChordEvent,
        chord: Chord,
        type: ExerciseType,
        prompt: String,
        options: List<String>,
        answer: String,
    ) = Exercise(
        id = "${event.id}:${type.name}",
        projectId = source.projectId,
        projectTitle = source.projectTitle,
        type = type,
        sourceEventId = event.id,
        excerptStartMs = event.startMs,
        excerptEndMs = event.endMs,
        prompt = prompt,
        options = options,
        correctAnswer = answer,
        answerChord = chord,
        confidenceAtGeneration = event.confidence,
    )

    private fun qualityLabel(chord: Chord): String = when {
        chord.isHalfDiminished -> "Half-diminished"
        chord.quality == ChordQuality.DIMINISHED -> "Diminished"
        chord.quality == ChordQuality.AUGMENTED -> "Augmented"
        chord.quality == ChordQuality.SUSPENDED -> "Suspended"
        chord.quality == ChordQuality.MINOR && chord.seventh == SeventhType.MINOR -> "Minor seventh"
        chord.quality == ChordQuality.MINOR -> "Minor"
        chord.seventh == SeventhType.MAJOR -> "Major seventh"
        chord.seventh == SeventhType.MINOR -> "Dominant seventh"
        chord.sixth -> "Major sixth"
        else -> "Major"
    }

    private companion object {
        const val MaxAttemptsPerSlot = 4
        const val BoundaryToleranceMs = 60L

        val QualityLabels = listOf(
            "Major", "Minor", "Dominant seventh", "Major seventh", "Minor seventh",
            "Diminished", "Half-diminished", "Augmented", "Suspended", "Major sixth",
        )
    }
}

/** Stored history, so progress survives and a weak topic can be found again. */
data class EarTrainingSessionRecord(
    val id: String,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val projectScope: String?,
    val score: Float,
    val total: Int,
)

data class SkillAccuracy(val type: ExerciseType, val total: Int, val correct: Int) {
    val accuracy: Float get() = if (total == 0) 0f else correct.toFloat() / total
}

interface EarTrainingRepository {
    fun observeSessions(): kotlinx.coroutines.flow.Flow<List<EarTrainingSessionRecord>>
    fun observeSkillAccuracy(): kotlinx.coroutines.flow.Flow<List<SkillAccuracy>>

    suspend fun startSession(projectScope: String?): String
    suspend fun recordAttempt(sessionId: String, attempt: ExerciseAttempt)
    suspend fun completeSession(sessionId: String, summary: SessionSummary)
    suspend fun clearHistory()
}

/** True when a chart contains anything worth asking about. */
fun ExerciseGenerator.hasEligibleMaterial(chart: SongChart): Boolean =
    EligibilityRules().eligibleEvents(chart).isNotEmpty()
