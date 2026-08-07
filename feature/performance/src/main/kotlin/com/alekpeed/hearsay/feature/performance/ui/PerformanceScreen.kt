package com.alekpeed.hearsay.feature.performance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alekpeed.hearsay.feature.performance.PerformanceActions
import com.alekpeed.hearsay.feature.performance.PerformanceUiState
import com.alekpeed.hearsay.feature.performance.PerformanceViewModel

@Composable
fun PerformanceRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PerformanceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.preparePlayback() }

    PerformanceScreen(
        state = state,
        actions = viewModel.actions,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    state: PerformanceUiState,
    actions: PerformanceActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (state as? PerformanceUiState.Ready)?.project?.project?.title ?: "Project",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            PerformanceUiState.Loading -> LoadingState(Modifier.padding(padding))
            PerformanceUiState.ProjectMissing -> MessageState(
                title = "This project is gone",
                body = "It was deleted, or its data could not be read.",
                modifier = Modifier.padding(padding),
            )

            is PerformanceUiState.Ready -> ReadyContent(
                state = state,
                actions = actions,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ReadyContent(
    state: PerformanceUiState.Ready,
    actions: PerformanceActions,
    modifier: Modifier = Modifier,
) {
    var editingRowIndex by remember { mutableStateOf<Int?>(null) }
    var showManualChartDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // The table follows the music, unless the player has taken over scrolling themselves.
    LaunchedEffect(state.currentRowIndex, state.autoScroll) {
        if (state.autoScroll && state.currentRowIndex >= 0) {
            listState.animateScrollToItem(index = state.currentRowIndex, scrollOffset = -AutoScrollLeadPx)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = if (maxWidth >= ExpandedWidthThreshold) TableDensity.EXPANDED else TableDensity.COMPACT

        Column(modifier = Modifier.fillMaxSize()) {
            state.sourceProblem?.let { problem ->
                SourceProblemBanner(problem = problem, modifier = Modifier.fillMaxWidth())
            }

            PerformanceHeader(state = state, modifier = Modifier.fillMaxWidth())

            TransportBar(
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )

            PracticeControls(
                state = state,
                actions = actions,
                onEditSelected = { editingRowIndex = state.selectedRowIndex },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )

            if (!state.hasChart) {
                NoChartState(
                    analysis = state.analysis,
                    canAnalyze = state.project.isPlayable,
                    onAnalyze = actions::onAnalyze,
                    onCancelAnalysis = actions::onCancelAnalysis,
                    onCreateManualChart = { showManualChartDialog = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ChordTable(
                    rows = state.rows,
                    currentRowIndex = state.currentRowIndex,
                    selectedRowIndex = state.selectedRowIndex,
                    chordsHidden = state.chordsHidden,
                    density = density,
                    listState = listState,
                    onRowSelected = actions::onRowSelected,
                    onRowDoubleTapped = actions::onRowPlayFrom,
                    modifier = Modifier.fillMaxSize().testTag(ChordTableTestTag),
                )
            }
        }
    }

    PerformanceDialogs(
        state = state,
        actions = actions,
        editingRowIndex = editingRowIndex,
        showManualChartDialog = showManualChartDialog,
        onDismissEdit = { editingRowIndex = null },
        onDismissManualChart = { showManualChartDialog = false },
    )
}

/** The sheets and dialogs the table can raise, kept out of the layout that raises them. */
@Composable
private fun PerformanceDialogs(
    state: PerformanceUiState.Ready,
    actions: PerformanceActions,
    editingRowIndex: Int?,
    showManualChartDialog: Boolean,
    onDismissEdit: () -> Unit,
    onDismissManualChart: () -> Unit,
) {
    editingRowIndex?.let { index ->
        state.rows.getOrNull(index)?.let { row ->
            ChordEditSheet(
                row = row,
                transposeSemitones = state.display.transposeSemitones,
                alternatives = state.alternativesFor(row),
                onDismiss = onDismissEdit,
                onSubmit = { symbol ->
                    actions.onChordEdited(index, symbol)
                    onDismissEdit()
                },
                onSelectAlternative = { alternative ->
                    actions.onSelectAlternative(index, alternative)
                    onDismissEdit()
                },
                onConfirm = { actions.onChordConfirmed(index, true) },
                onSplit = {
                    actions.onSplitAtPlayhead(index)
                    onDismissEdit()
                },
                onMerge = {
                    actions.onMergeWithNext(index)
                    onDismissEdit()
                },
                onRestoreMachineResult = actions::onRestoreMachineResult,
            )
        }
    }

    if (showManualChartDialog) {
        ManualChartDialog(
            onDismiss = onDismissManualChart,
            onConfirm = { bpm, beatsPerMeasure ->
                actions.onCreateManualChart(bpm, beatsPerMeasure)
                onDismissManualChart()
            },
        )
    }
}

@Composable
private fun TransportBar(
    state: PerformanceUiState.Ready,
    actions: PerformanceActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::onPreviousMeasure, modifier = Modifier.size(TouchTargetSize)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous measure")
        }

        FilledIconButton(
            onClick = actions::onPlayPause,
            modifier = Modifier.size(TouchTargetSize).testTag(PlayPauseTestTag),
        ) {
            if (state.playback.isPlaying) {
                Icon(Icons.Filled.Pause, contentDescription = "Pause")
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
            }
        }

        IconButton(onClick = actions::onNextMeasure, modifier = Modifier.size(TouchTargetSize)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next measure")
        }

        Text(
            text = "${formatTime(state.playback.positionMs)} / ${formatTime(state.playback.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

internal const val ChordTableTestTag = "chord-table"
internal const val PlayPauseTestTag = "play-pause"

private val TouchTargetSize = 56.dp
private val ExpandedWidthThreshold = 720.dp
private const val AutoScrollLeadPx = 240
