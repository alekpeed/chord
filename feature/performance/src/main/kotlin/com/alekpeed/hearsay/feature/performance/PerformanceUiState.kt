package com.alekpeed.hearsay.feature.performance

import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.playback.PlaybackState
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.repository.ChordAlternative
import com.alekpeed.hearsay.core.model.timeline.ChartDisplayOptions
import com.alekpeed.hearsay.core.model.timeline.ChartRow

/** What the performance screen shows. One immutable value; the screen renders it and nothing else. */
sealed interface PerformanceUiState {

    data object Loading : PerformanceUiState

    data object ProjectMissing : PerformanceUiState

    data class Ready(
        val project: ProjectWithSource,
        val rows: List<ChartRow>,
        val currentRowIndex: Int,
        val selectedRowIndex: Int?,
        val playback: PlaybackState,
        val display: ChartDisplayOptions,
        val autoScroll: Boolean,
        val chordsHidden: Boolean,
        val sourceProblem: SourceProblem?,
        val analysis: AnalysisJob?,
        val alternatives: Map<String, List<ChordAlternative>>,
    ) : PerformanceUiState {

        val isAnalyzing: Boolean get() = analysis?.isActive == true

        val hasChart: Boolean get() = rows.isNotEmpty()

        val currentRow: ChartRow? get() = rows.getOrNull(currentRowIndex)

        val selectedRow: ChartRow? get() = selectedRowIndex?.let { rows.getOrNull(it) }

        fun alternativesFor(row: ChartRow): List<ChordAlternative> = alternatives[row.eventId].orEmpty()

        val tempoLabel: String? get() = project.project.tempoBpm?.let { "${it.toInt()} BPM" }

        val keyLabel: String? get() = project.project.keyLabel
    }
}

/** Reasons a project cannot play, kept distinct so the screen can offer the right way out. */
enum class SourceProblem { MISSING_FILE, PERMISSION_LOST, DECODER_UNSUPPORTED, UNKNOWN_PLAYBACK_ERROR }

/** Everything the screen can ask the ViewModel to do. */
interface PerformanceActions {
    fun onPlayPause()
    fun onSeekTo(positionMs: Long)
    fun onNextMeasure()
    fun onPreviousMeasure()
    fun onRowSelected(index: Int)
    fun onRowPlayFrom(index: Int)
    fun onSpeedChange(speed: Float)
    fun onTransposeChange(semitones: Int)
    fun onNotationChange(display: ChartDisplayOptions)
    fun onAutoScrollChange(enabled: Boolean)
    fun onChordsHiddenChange(hidden: Boolean)
    fun onLoopSelectedRow()
    fun onClearLoop()
    fun onChordEdited(rowIndex: Int, symbol: String)
    fun onChordConfirmed(rowIndex: Int, confirmed: Boolean)
    fun onRestoreMachineResult()
    fun onCreateManualChart(bpm: Float, beatsPerMeasure: Int)
    fun onSelectAlternative(rowIndex: Int, alternative: ChordAlternative)
    fun onSplitAtPlayhead(rowIndex: Int)
    fun onMergeWithNext(rowIndex: Int)
    fun onAnalyze(profile: AnalysisProfile)
    fun onCancelAnalysis()
}
