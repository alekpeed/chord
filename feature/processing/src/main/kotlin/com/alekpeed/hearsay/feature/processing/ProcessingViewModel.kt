package com.alekpeed.hearsay.feature.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.analysis.AnalysisRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ProcessingUiState {
    data class Entry(val job: AnalysisJob, val title: String)

    data class Idle(val recent: List<Entry>) : ProcessingUiState
    data class Working(val jobs: List<Entry>) : ProcessingUiState
}

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    private val analysisRepository: AnalysisRepository,
    private val projectRepository: ProjectRepository,
    private val engine: AnalysisEngine,
) : ViewModel() {

    init {
        // A job the system killed is not running. Clearing that on entry keeps the queue truthful —
        // excluding whatever this process is genuinely working on, which is not orphaned at all.
        viewModelScope.launch {
            analysisRepository.recoverOrphanedJobs(exceptProjectIds = engine.activeProjectIds.value)
        }
    }

    val uiState: StateFlow<ProcessingUiState> = combine(
        analysisRepository.observeActiveJobs(),
        projectRepository.observeLibrary(),
    ) { jobs, library ->
        val titles = library.associate { it.project.id to it.project.title }
        val entries = jobs.map { ProcessingUiState.Entry(it, titles[it.projectId] ?: "Untitled") }
        if (entries.any { it.job.isActive }) {
            ProcessingUiState.Working(entries)
        } else {
            ProcessingUiState.Idle(entries)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMs), ProcessingUiState.Idle(emptyList()))

    fun cancel(projectId: String) = engine.cancel(projectId)

    private companion object {
        const val StopTimeoutMs = 5_000L
    }
}
