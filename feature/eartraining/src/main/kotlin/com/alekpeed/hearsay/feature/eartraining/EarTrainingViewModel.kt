package com.alekpeed.hearsay.feature.eartraining

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.model.eartraining.EarTrainingRepository
import com.alekpeed.hearsay.core.model.eartraining.EarTrainingSessionRecord
import com.alekpeed.hearsay.core.model.eartraining.Exercise
import com.alekpeed.hearsay.core.model.eartraining.ExerciseAttempt
import com.alekpeed.hearsay.core.model.eartraining.ExerciseGenerator
import com.alekpeed.hearsay.core.model.eartraining.ExerciseType
import com.alekpeed.hearsay.core.model.eartraining.ListeningMode
import com.alekpeed.hearsay.core.model.eartraining.SessionSummary
import com.alekpeed.hearsay.core.model.eartraining.SkillAccuracy
import com.alekpeed.hearsay.core.model.eartraining.hasEligibleMaterial
import com.alekpeed.hearsay.core.model.playback.PlaybackController
import com.alekpeed.hearsay.core.model.playback.PlaybackRequest
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import com.alekpeed.hearsay.core.model.timeline.LoopRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface EarTrainingUiState {

    /** Nothing analysed and confident enough to ask about yet. */
    data class NotReady(val reason: String, val history: History) : EarTrainingUiState

    data class Ready(val eligibleProjects: Int, val history: History) : EarTrainingUiState

    data class InSession(
        val exercise: Exercise,
        val index: Int,
        val total: Int,
        val revealed: Boolean,
        val chosenAnswer: String?,
        val listeningMode: ListeningMode,
        val replayCount: Int,
        val correctSoFar: Int,
    ) : EarTrainingUiState

    data class Finished(val summary: SessionSummary, val history: History) : EarTrainingUiState

    data class History(
        val sessions: List<EarTrainingSessionRecord>,
        val skills: List<SkillAccuracy>,
    ) {
        /** The type with the worst record and enough attempts to mean anything. */
        val weakest: SkillAccuracy? get() = skills.filter { it.total >= 4 }.minByOrNull { it.accuracy }
    }
}

@HiltViewModel
class EarTrainingViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val chartRepository: ChartRepository,
    private val earTrainingRepository: EarTrainingRepository,
    private val playbackController: PlaybackController,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val generator = ExerciseGenerator()

    private val session = MutableStateFlow<SessionState?>(null)
    private val history = combine(
        earTrainingRepository.observeSessions(),
        earTrainingRepository.observeSkillAccuracy(),
    ) { sessions, skills -> EarTrainingUiState.History(sessions, skills) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(StopTimeoutMs),
            EarTrainingUiState.History(emptyList(), emptyList()),
        )

    private val eligibleProjects = MutableStateFlow(0)

    val uiState: StateFlow<EarTrainingUiState> = combine(
        session,
        history,
        eligibleProjects,
    ) { active, history, eligible ->
        when {
            active == null && eligible == 0 -> EarTrainingUiState.NotReady(
                reason = "Analyse a song first. Questions are only built from chords the app is " +
                    "confident about, or ones you have confirmed yourself.",
                history = history,
            )

            active == null -> EarTrainingUiState.Ready(eligible, history)

            active.finished -> EarTrainingUiState.Finished(SessionSummary(active.attempts), history)

            else -> EarTrainingUiState.InSession(
                exercise = active.exercises[active.index],
                index = active.index,
                total = active.exercises.size,
                revealed = active.revealed,
                chosenAnswer = active.chosenAnswer,
                listeningMode = active.listeningMode,
                replayCount = active.replayCount,
                correctSoFar = active.attempts.count { it.correct },
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(StopTimeoutMs),
        EarTrainingUiState.Ready(0, EarTrainingUiState.History(emptyList(), emptyList())),
    )

    init {
        viewModelScope.launch { eligibleProjects.value = collectSources().size }
    }

    fun startSession(types: Set<ExerciseType>, count: Int = DefaultQuestionCount) {
        viewModelScope.launch {
            val sources = collectSources()
            val exercises = generator.generate(sources, types, count)
            if (exercises.isEmpty()) {
                eligibleProjects.value = 0
                return@launch
            }
            val sessionId = earTrainingRepository.startSession(projectScope = null)
            session.value = SessionState(
                sessionId = sessionId,
                exercises = exercises,
                projects = sources.associate { it.projectId to it.projectTitle },
                startedAtMs = timeProvider.nowMs(),
            )
            playCurrent()
        }
    }

    fun replay() {
        session.value = session.value?.copy(replayCount = (session.value?.replayCount ?: 0) + 1)
        playCurrent()
    }

    fun setListeningMode(mode: ListeningMode) {
        session.value = session.value?.copy(listeningMode = mode)
    }

    fun answer(choice: String) {
        val active = session.value ?: return
        if (active.revealed) return

        val exercise = active.exercises[active.index]
        val attempt = ExerciseAttempt(
            exercise = exercise,
            answer = choice,
            correct = exercise.isCorrect(choice),
            replayCount = active.replayCount,
            listeningMode = active.listeningMode,
            responseTimeMs = timeProvider.nowMs() - active.questionStartedAtMs,
        )

        session.value = active.copy(
            revealed = true,
            chosenAnswer = choice,
            attempts = active.attempts + attempt,
        )
        viewModelScope.launch { earTrainingRepository.recordAttempt(active.sessionId, attempt) }
    }

    fun next() {
        val active = session.value ?: return
        if (active.index + 1 >= active.exercises.size) {
            playbackController.setLoop(null)
            playbackController.pause()
            session.value = active.copy(finished = true)
            viewModelScope.launch {
                earTrainingRepository.completeSession(active.sessionId, SessionSummary(active.attempts))
            }
            return
        }
        session.value = active.copy(
            index = active.index + 1,
            revealed = false,
            chosenAnswer = null,
            replayCount = 0,
            questionStartedAtMs = timeProvider.nowMs(),
        )
        playCurrent()
    }

    fun endSession() {
        playbackController.setLoop(null)
        playbackController.pause()
        session.value = null
    }

    /**
     * Plays the excerpt this question is about, looped, so the user can listen as many times as
     * they want without leaving the question.
     */
    private fun playCurrent() {
        val active = session.value ?: return
        val exercise = active.exercises[active.index]
        viewModelScope.launch {
            val project = projectRepository.getProject(exercise.projectId) ?: return@launch
            val source = project.source ?: return@launch
            playbackController.prepare(
                PlaybackRequest(
                    mediaId = project.project.id,
                    uri = source.uri,
                    title = project.project.title,
                    artist = project.project.artist,
                    durationMs = project.project.durationMs,
                ),
            )
            playbackController.setLoop(LoopRange(exercise.excerptStartMs, exercise.excerptEndMs))
            playbackController.seekTo(exercise.excerptStartMs)
            playbackController.play()
        }
    }

    private suspend fun collectSources(): List<ExerciseGenerator.Source> {
        val projects: List<ProjectWithSource> = projectRepository.observeLibrary().first()
        return projects.mapNotNull { project ->
            val chart = chartRepository.observeChart(project.project.id).first()
            if (chart.chordEvents.isEmpty()) return@mapNotNull null
            ExerciseGenerator.Source(project.project.id, project.project.title, chart)
        }.filter { generator.hasEligibleMaterial(it.chart) }
    }

    private data class SessionState(
        val sessionId: String,
        val exercises: List<Exercise>,
        val projects: Map<String, String>,
        val startedAtMs: Long,
        val index: Int = 0,
        val revealed: Boolean = false,
        val chosenAnswer: String? = null,
        val replayCount: Int = 0,
        val listeningMode: ListeningMode = ListeningMode.FULL_MIX,
        val attempts: List<ExerciseAttempt> = emptyList(),
        val finished: Boolean = false,
        val questionStartedAtMs: Long = startedAtMs,
    )

    private companion object {
        const val StopTimeoutMs = 5_000L
        const val DefaultQuestionCount = 10
    }
}
