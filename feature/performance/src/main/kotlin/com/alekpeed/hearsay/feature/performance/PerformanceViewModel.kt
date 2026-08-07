package com.alekpeed.hearsay.feature.performance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.playback.PlaybackController
import com.alekpeed.hearsay.core.model.playback.PlaybackError
import com.alekpeed.hearsay.core.model.playback.PlaybackRequest
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import com.alekpeed.hearsay.core.model.timeline.ChartDisplayOptions
import com.alekpeed.hearsay.core.model.timeline.ChartRow
import com.alekpeed.hearsay.core.model.timeline.ChartRowBuilder
import com.alekpeed.hearsay.core.model.timeline.ChordNotation
import com.alekpeed.hearsay.core.model.timeline.LoopRange
import com.alekpeed.hearsay.core.model.timeline.SongChart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the performance screen.
 *
 * Table rows are rebuilt only when the chart or the display settings change; the playback position
 * arrives many times a second and is used solely to pick which existing row is current. Doing it the
 * other way round would re-render every chord symbol fifty times a second for nothing.
 */
@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val chartRepository: ChartRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val projectId: String = requireNotNull(savedStateHandle[ProjectIdKey]) {
        "PerformanceViewModel needs a $ProjectIdKey argument"
    }

    private val display = MutableStateFlow(
        ChartDisplayOptions(
            notation = savedStateHandle.get<String>(NotationKey)
                ?.let { runCatching { ChordNotation.valueOf(it) }.getOrNull() }
                ?: ChordNotation.SYMBOL,
            transposeSemitones = savedStateHandle[TransposeKey] ?: 0,
        ),
    )
    private val autoScroll = MutableStateFlow(savedStateHandle[AutoScrollKey] ?: true)
    private val chordsHidden = MutableStateFlow(savedStateHandle[ChordsHiddenKey] ?: false)
    private val selectedRowIndex = MutableStateFlow<Int?>(savedStateHandle[SelectedRowKey])

    private val project: StateFlow<ProjectWithSource?> = projectRepository.observeProject(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMs), null)

    private val chart: StateFlow<SongChart> = chartRepository.observeChart(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMs), SongChart.Empty)

    private val rows: StateFlow<List<ChartRow>> = combine(chart, display) { chart, options ->
        ChartRowBuilder.build(chart, options)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMs), emptyList())

    val uiState: StateFlow<PerformanceUiState> = combine(
        combine(project, rows, chart) { project, rows, chart -> Triple(project, rows, chart) },
        playbackController.state,
        display,
        combine(autoScroll, chordsHidden, selectedRowIndex) { auto, hidden, selected ->
            Triple(auto, hidden, selected)
        },
    ) { (project, rows, chart), playback, options, (auto, hidden, selected) ->
        when {
            project == null -> PerformanceUiState.Loading
            else -> PerformanceUiState.Ready(
                project = project,
                rows = rows,
                currentRowIndex = chart.indexOfChordAt(playback.positionMs),
                selectedRowIndex = selected,
                playback = playback,
                display = options,
                autoScroll = auto,
                chordsHidden = hidden,
                sourceProblem = sourceProblemOf(project, playback.error),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMs), PerformanceUiState.Loading)

    /** Opens the project's audio. Safe to call repeatedly; the controller ignores a repeat request. */
    fun preparePlayback() {
        viewModelScope.launch {
            val current = projectRepository.getProject(projectId) ?: return@launch
            val source = current.source ?: return@launch
            if (source.availability != SourceAvailability.AVAILABLE) return@launch
            playbackController.prepare(
                PlaybackRequest(
                    mediaId = current.project.id,
                    uri = source.uri,
                    title = current.project.title,
                    artist = current.project.artist,
                    durationMs = current.project.durationMs,
                ),
            )
        }
    }

    val actions: PerformanceActions = object : PerformanceActions {

        override fun onPlayPause() {
            if (playbackController.state.value.isPlaying) playbackController.pause() else playbackController.play()
        }

        override fun onSeekTo(positionMs: Long) = playbackController.seekTo(positionMs)

        override fun onNextMeasure() {
            val target = chart.value.nextMeasureStartMs(playbackController.state.value.positionMs) ?: return
            playbackController.seekTo(target)
        }

        override fun onPreviousMeasure() {
            val target = chart.value.previousMeasureStartMs(playbackController.state.value.positionMs) ?: return
            playbackController.seekTo(target)
        }

        override fun onRowSelected(index: Int) {
            selectedRowIndex.value = index
            savedStateHandle[SelectedRowKey] = index
        }

        override fun onRowPlayFrom(index: Int) {
            val row = rows.value.getOrNull(index) ?: return
            playbackController.seekTo(row.startMs)
        }

        override fun onSpeedChange(speed: Float) =
            playbackController.setSpeed(speed.coerceIn(MinSpeed, MaxSpeed))

        override fun onTransposeChange(semitones: Int) {
            val clamped = semitones.coerceIn(-MaxTranspose, MaxTranspose)
            display.value = display.value.copy(transposeSemitones = clamped)
            savedStateHandle[TransposeKey] = clamped
        }

        override fun onNotationChange(display: ChartDisplayOptions) {
            this@PerformanceViewModel.display.value = display
            savedStateHandle[NotationKey] = display.notation.name
        }

        override fun onAutoScrollChange(enabled: Boolean) {
            autoScroll.value = enabled
            savedStateHandle[AutoScrollKey] = enabled
        }

        override fun onChordsHiddenChange(hidden: Boolean) {
            chordsHidden.value = hidden
            savedStateHandle[ChordsHiddenKey] = hidden
        }

        override fun onLoopSelectedRow() {
            val row = selectedRowIndex.value?.let { rows.value.getOrNull(it) } ?: return
            playbackController.setLoop(LoopRange(row.startMs, row.endMs))
            playbackController.seekTo(row.startMs)
        }

        override fun onClearLoop() = playbackController.setLoop(null)

        override fun onChordEdited(rowIndex: Int, symbol: String) {
            val row = rows.value.getOrNull(rowIndex) ?: return
            val transpose = display.value.transposeSemitones
            // What the user typed is what they see, which may be transposed. Store concert pitch.
            val typed = if (ChordParser.isNoChord(symbol)) null else ChordParser.parse(symbol) ?: return
            val stored = typed?.transposedBy(-transpose)
            viewModelScope.launch { chartRepository.updateChord(projectId, row.eventId, stored) }
        }

        override fun onChordConfirmed(rowIndex: Int, confirmed: Boolean) {
            val row = rows.value.getOrNull(rowIndex) ?: return
            viewModelScope.launch { chartRepository.confirmChord(projectId, row.eventId, confirmed) }
        }

        override fun onRestoreMachineResult() {
            viewModelScope.launch { chartRepository.restoreMachineResult(projectId) }
        }

        override fun onCreateManualChart(bpm: Float, beatsPerMeasure: Int) {
            viewModelScope.launch {
                val current = projectRepository.getProject(projectId) ?: return@launch
                chartRepository.replaceChart(
                    projectId = projectId,
                    chart = com.alekpeed.hearsay.core.model.chart.ManualChart.blankGrid(
                        durationMs = current.project.durationMs,
                        bpm = bpm,
                        beatsPerMeasure = beatsPerMeasure,
                    ),
                    label = "Manual chart at ${bpm.toInt()} BPM",
                    revisionSourceIsUser = true,
                )
            }
        }
    }

    private fun sourceProblemOf(project: ProjectWithSource, error: PlaybackError?): SourceProblem? = when {
        project.source == null -> SourceProblem.MISSING_FILE
        project.source?.availability == SourceAvailability.MISSING -> SourceProblem.MISSING_FILE
        project.source?.availability == SourceAvailability.PERMISSION_LOST -> SourceProblem.PERMISSION_LOST
        error is PlaybackError.DecoderUnsupported -> SourceProblem.DECODER_UNSUPPORTED
        error is PlaybackError.SourceUnavailable -> SourceProblem.MISSING_FILE
        error is PlaybackError.Unknown -> SourceProblem.UNKNOWN_PLAYBACK_ERROR
        else -> null
    }

    companion object {
        const val ProjectIdKey = "projectId"

        private const val NotationKey = "notation"
        private const val TransposeKey = "transpose"
        private const val AutoScrollKey = "autoScroll"
        private const val ChordsHiddenKey = "chordsHidden"
        private const val SelectedRowKey = "selectedRow"

        private const val StopTimeoutMs = 5_000L
        const val MinSpeed = 0.25f
        const val MaxSpeed = 2f
        const val MaxTranspose = 11
    }
}
