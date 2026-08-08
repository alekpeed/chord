package com.alekpeed.hearsay.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.ImportFailure
import com.alekpeed.hearsay.core.model.repository.ImportResult
import com.alekpeed.hearsay.core.model.repository.MediaImportRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Content(
        val projects: List<ProjectWithSource>,
        val query: String,
        val isImporting: Boolean,
    ) : LibraryUiState
}

/** Something that happened once and should be said once. */
sealed interface LibraryMessage {
    data class Imported(val title: String) : LibraryMessage
    data class Duplicate(val title: String, val projectId: String) : LibraryMessage
    data class Failed(val reason: String) : LibraryMessage
    data class Deleted(val title: String) : LibraryMessage
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val importRepository: MediaImportRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val importing = MutableStateFlow(false)
    private val _messages = MutableStateFlow<LibraryMessage?>(null)
    val messages: StateFlow<LibraryMessage?> = _messages

    val uiState: StateFlow<LibraryUiState> = combine(
        projectRepository.observeLibrary(),
        query,
        importing,
    ) { projects, query, isImporting ->
        val filtered = projects.filter { it.matches(query) }
        when {
            projects.isEmpty() && !isImporting -> LibraryUiState.Empty
            else -> LibraryUiState.Content(filtered, query, isImporting)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMs), LibraryUiState.Loading)

    val hasProjects: StateFlow<Boolean> = projectRepository.observeLibrary()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMs), false)

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onImport(uri: String, storageMode: StorageMode, profile: AnalysisProfile = AnalysisProfile.BALANCED) {
        viewModelScope.launch {
            importing.value = true
            val result = try {
                importRepository.import(uri, storageMode, profile)
            } finally {
                importing.value = false
            }
            _messages.value = when (result) {
                is ImportResult.Success ->
                    LibraryMessage.Imported(projectRepository.getProject(result.projectId)?.project?.title.orEmpty())

                is ImportResult.AlreadyImported -> LibraryMessage.Duplicate(result.title, result.projectId)
                is ImportResult.Failed -> LibraryMessage.Failed(result.reason.describe())
            }
        }
    }

    fun onRelink(projectId: String, uri: String, storageMode: StorageMode) {
        viewModelScope.launch { projectRepository.relinkSource(projectId, uri, storageMode) }
    }

    fun onDelete(project: ProjectWithSource) {
        viewModelScope.launch {
            projectRepository.deleteProject(project.project.id)
            _messages.value = LibraryMessage.Deleted(project.project.title)
        }
    }

    fun onMessageShown() {
        _messages.value = null
    }

    private fun ProjectWithSource.matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return listOfNotNull(project.title, project.artist, project.album, project.keyLabel)
            .any { it.lowercase().contains(needle) } ||
            project.tags.any { it.lowercase().contains(needle) }
    }

    private fun ImportFailure.describe(): String = when (this) {
        ImportFailure.PermissionDenied ->
            "Android would not grant lasting access to that file. Try copying it into the app instead."

        ImportFailure.UnsupportedMedia -> "That file has no audio this device can read."
        ImportFailure.InsufficientStorage -> "Not enough free space to copy that file."
        is ImportFailure.Unreadable -> message
    }

    private companion object {
        const val StopTimeoutMs = 5_000L
    }
}
