package com.alekpeed.hearsay.feature.eartraining

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.model.eartraining.EarTrainingRepository
import com.alekpeed.hearsay.core.model.eartraining.EarTrainingSessionRecord
import com.alekpeed.hearsay.core.model.eartraining.ExerciseAttempt
import com.alekpeed.hearsay.core.model.eartraining.ExerciseType
import com.alekpeed.hearsay.core.model.eartraining.SessionSummary
import com.alekpeed.hearsay.core.model.eartraining.SkillAccuracy
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.playback.PlaybackController
import com.alekpeed.hearsay.core.model.playback.PlaybackRequest
import com.alekpeed.hearsay.core.model.playback.PlaybackState
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.MediaAsset
import com.alekpeed.hearsay.core.model.project.MediaRole
import com.alekpeed.hearsay.core.model.project.Project
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.LoopRange
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EarTrainingViewModelTest {

    private val playback = RecordingPlaybackController()
    private val earTraining = RecordingEarTrainingRepository()
    private var chart = confidentChart()

    private fun confidentChart(confidence: Float = 0.95f) = SongChart.of(
        chordEvents = listOf("Cmaj7", "Am7", "Dm7", "G7").mapIndexed { index, symbol ->
            ChordEvent(
                id = "e$index",
                startMs = index * 2000L,
                endMs = (index + 1) * 2000L,
                chord = ChordParser.parse(symbol),
                confidence = confidence,
            )
        },
        beats = (0 until 16).map { BeatEvent(it * 500L, it % 4 + 1, it / 4 + 1) },
    )

    private val projects = object : ProjectRepository {
        val library = MutableStateFlow(listOf(project()))
        override fun observeLibrary(): Flow<List<ProjectWithSource>> = library
        override fun observeProject(projectId: String) =
            library.map { list -> list.firstOrNull { it.project.id == projectId } }
        override suspend fun getProject(projectId: String) =
            library.value.firstOrNull { it.project.id == projectId }
        override suspend fun updateMetadata(projectId: String, title: String, artist: String?, album: String?) = Unit
        override suspend fun updateAvailability(mediaAssetId: String, availability: SourceAvailability) = Unit
        override suspend fun relinkSource(projectId: String, uri: String, storageMode: StorageMode) = Unit
        override suspend fun markPracticed(projectId: String, atMs: Long) = Unit
        override suspend fun deleteProject(projectId: String) = Unit
    }

    private val charts = object : ChartRepository {
        override fun observeChart(projectId: String): Flow<SongChart> = MutableStateFlow(chart)
        override suspend fun revisions(projectId: String) = emptyList<com.alekpeed.hearsay.core.model.project.Revision>()
        override suspend fun replaceChart(
            projectId: String,
            chart: SongChart,
            label: String,
            revisionSourceIsUser: Boolean,
        ) = "r1"
        override suspend fun updateChord(
            projectId: String,
            eventId: String,
            chord: com.alekpeed.hearsay.core.model.music.Chord?,
        ) = "r1"
        override suspend fun confirmChord(projectId: String, eventId: String, confirmed: Boolean) = Unit
        override suspend fun restoreMachineResult(projectId: String): String? = null
        override suspend fun setActiveRevision(projectId: String, revisionId: String) = Unit
        override fun observeAlternatives(projectId: String) = MutableStateFlow(
            emptyMap<String, List<com.alekpeed.hearsay.core.model.repository.ChordAlternative>>(),
        )
        override suspend fun replaceAlternatives(
            projectId: String,
            alternatives: List<com.alekpeed.hearsay.core.model.repository.ChordAlternative>,
        ) = Unit
        override suspend fun splitChordRegion(projectId: String, eventId: String, atMs: Long) = "r1"
        override suspend fun mergeWithNext(projectId: String, eventId: String) = "r1"
        override suspend fun moveBoundary(projectId: String, eventId: String, newStartMs: Long) = "r1"
        override suspend fun renameSection(projectId: String, sectionId: String, label: String) = "r1"
    }

    private var now = 1_000L
    private val time = TimeProvider { now += 250; now }

    private fun project() = ProjectWithSource(
        project = Project(
            id = "p1",
            title = "Autumn Leaves",
            artist = null,
            album = null,
            createdAtMs = 0,
            updatedAtMs = 0,
            durationMs = 8_000,
            analysisStatus = AnalysisStatus.COMPLETE,
            activeRevisionId = "r1",
        ),
        source = MediaAsset(
            "a1", "p1", MediaRole.SOURCE, "content://media/1", StorageMode.REFERENCED,
            "audio/flac", 8_000, 44_100, 2, 1_000, "sum", SourceAvailability.AVAILABLE, "leaves.flac",
        ),
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = EarTrainingViewModel(projects, charts, earTraining, playback, time)

    @Test
    fun `a library with confident analysis is ready to practice`() = runTest {
        viewModel().uiState.test {
            val ready = awaitState<EarTrainingUiState.Ready>()
            assertEquals(1, ready.eligibleProjects)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a library with only low-confidence analysis offers nothing`() = runTest {
        chart = confidentChart(confidence = 0.2f)
        viewModel().uiState.test {
            awaitState<EarTrainingUiState.NotReady>()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `starting a session asks the first question and plays its excerpt on a loop`() = runTest {
        val model = viewModel()
        model.uiState.test {
            awaitState<EarTrainingUiState.Ready>()
            model.startSession(setOf(ExerciseType.CHORD_QUALITY), count = 3)

            val question = awaitState<EarTrainingUiState.InSession>()
            assertEquals(0, question.index)
            assertEquals(3, question.total)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("content://media/1", playback.prepared.last().uri)
        val loop = playback.loops.last()
        assertEquals(loop?.startMs, playback.seeks.last())
    }

    @Test
    fun `answering reveals the result and records the attempt`() = runTest {
        val model = viewModel()
        model.uiState.test {
            awaitState<EarTrainingUiState.Ready>()
            model.startSession(setOf(ExerciseType.CHORD_QUALITY), count = 2)
            val question = awaitState<EarTrainingUiState.InSession>()

            model.answer(question.exercise.correctAnswer)
            val revealed = awaitStateWhere<EarTrainingUiState.InSession> { it.revealed }
            assertEquals(1, revealed.correctSoFar)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, earTraining.attempts.size)
        assertTrue(earTraining.attempts.single().correct)
    }

    @Test
    fun `a wrong answer is recorded as wrong and still reveals the right one`() = runTest {
        val model = viewModel()
        model.uiState.test {
            awaitState<EarTrainingUiState.Ready>()
            model.startSession(setOf(ExerciseType.CHORD_ROOT), count = 2)
            val question = awaitState<EarTrainingUiState.InSession>()

            model.answer(question.exercise.options.first { it != question.exercise.correctAnswer })
            val revealed = awaitStateWhere<EarTrainingUiState.InSession> { it.revealed }
            assertEquals(0, revealed.correctSoFar)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(earTraining.attempts.none { it.correct })
    }

    @Test
    fun `working through every question finishes the session with a summary`() = runTest {
        val model = viewModel()
        model.uiState.test {
            awaitState<EarTrainingUiState.Ready>()
            model.startSession(setOf(ExerciseType.CHORD_QUALITY), count = 2)

            repeat(2) {
                val question = awaitStateWhere<EarTrainingUiState.InSession> { !it.revealed }
                model.answer(question.exercise.correctAnswer)
                awaitStateWhere<EarTrainingUiState.InSession> { it.revealed }
                model.next()
            }

            val finished = awaitState<EarTrainingUiState.Finished>()
            assertEquals(2, finished.summary.total)
            assertEquals(1f, finished.summary.accuracy, 0.001f)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, earTraining.completed.size)
        // Looping stops when the session does, rather than leaving an excerpt cycling forever.
        assertEquals(null, playback.loops.last())
    }

    @Test
    fun `answering twice does not double-count`() = runTest {
        val model = viewModel()
        model.uiState.test {
            awaitState<EarTrainingUiState.Ready>()
            model.startSession(setOf(ExerciseType.CHORD_QUALITY), count = 2)
            val question = awaitState<EarTrainingUiState.InSession>()

            model.answer(question.exercise.correctAnswer)
            awaitStateWhere<EarTrainingUiState.InSession> { it.revealed }
            model.answer(question.exercise.correctAnswer)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, earTraining.attempts.size)
    }

    @Test
    fun `replaying counts and is recorded with the attempt`() = runTest {
        val model = viewModel()
        model.uiState.test {
            awaitState<EarTrainingUiState.Ready>()
            model.startSession(setOf(ExerciseType.CHORD_QUALITY), count = 2)
            val question = awaitState<EarTrainingUiState.InSession>()

            model.replay()
            model.replay()
            val replayed = awaitStateWhere<EarTrainingUiState.InSession> { it.replayCount == 2 }
            model.answer(replayed.exercise.correctAnswer)
            awaitStateWhere<EarTrainingUiState.InSession> { it.revealed }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, earTraining.attempts.single().replayCount)
    }

    @Test
    fun `every question is traceable to the chord it came from`() = runTest {
        val model = viewModel()
        model.uiState.test {
            awaitState<EarTrainingUiState.Ready>()
            model.startSession(ExerciseType.entries.toSet(), count = 4)
            val question = awaitState<EarTrainingUiState.InSession>()

            assertTrue(question.exercise.sourceEventId in chart.chordEvents.map { it.id })
            assertEquals("p1", question.exercise.projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- doubles -------------------------------------------------------------------------------

    private class RecordingPlaybackController : PlaybackController {
        private val _state = MutableStateFlow(PlaybackState(isConnected = true))
        override val state: StateFlow<PlaybackState> = _state
        val prepared = mutableListOf<PlaybackRequest>()
        val seeks = mutableListOf<Long>()
        val loops = mutableListOf<LoopRange?>()

        override fun prepare(request: PlaybackRequest) { prepared += request }
        override fun play() { _state.value = _state.value.copy(isPlaying = true) }
        override fun pause() { _state.value = _state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { seeks += positionMs }
        override fun setSpeed(speed: Float) = Unit
        override fun setLoop(loop: LoopRange?) { loops += loop }
        override fun release() = Unit
    }

    private class RecordingEarTrainingRepository : EarTrainingRepository {
        val attempts = mutableListOf<ExerciseAttempt>()
        val completed = mutableListOf<SessionSummary>()

        override fun observeSessions(): Flow<List<EarTrainingSessionRecord>> = MutableStateFlow(emptyList())
        override fun observeSkillAccuracy(): Flow<List<SkillAccuracy>> = MutableStateFlow(emptyList())
        override suspend fun startSession(projectScope: String?): String = "session-1"
        override suspend fun recordAttempt(sessionId: String, attempt: ExerciseAttempt) { attempts += attempt }
        override suspend fun completeSession(sessionId: String, summary: SessionSummary) { completed += summary }
        override suspend fun clearHistory() = Unit
    }

    private suspend inline fun <reified T : EarTrainingUiState> ReceiveTurbine<EarTrainingUiState>.awaitState(): T {
        repeat(MaxEmissions) {
            val item = awaitItem()
            if (item is T) return item
        }
        error("No ${T::class.simpleName} within $MaxEmissions emissions")
    }

    private suspend inline fun <reified T : EarTrainingUiState> ReceiveTurbine<EarTrainingUiState>.awaitStateWhere(
        predicate: (T) -> Boolean,
    ): T {
        repeat(MaxEmissions) {
            val item = awaitItem()
            if (item is T && predicate(item)) return item
        }
        error("No matching ${T::class.simpleName} within $MaxEmissions emissions")
    }

    companion object {
        const val MaxEmissions = 30
    }
}
