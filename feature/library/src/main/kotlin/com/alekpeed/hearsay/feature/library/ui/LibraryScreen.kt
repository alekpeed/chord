package com.alekpeed.hearsay.feature.library.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.feature.library.LibraryMessage
import com.alekpeed.hearsay.feature.library.LibraryUiState
import com.alekpeed.hearsay.feature.library.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Supplied by the app module, which is the only one that can see the generated BuildConfig.
    // Null in a preview or a test, where there is no build to identify.
    versionLabel: String? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingUri by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        // Any audio, plus video containers whose audio track the app can analyze later.
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingUri = uri?.toString() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showMessage(it)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    // In the bar rather than in the list: the list scrolls and can be empty, and a
                    // version you have to scroll to find is one nobody quotes in a bug report.
                    Column {
                        Text("Library")
                        if (versionLabel != null) {
                            Text(
                                text = versionLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(VersionTestTag),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(SupportedMimeTypes) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add song") },
                modifier = Modifier.testTag(AddSongTestTag),
            )
        },
    ) { padding ->
        LibraryContent(
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onOpenProject = onOpenProject,
            onDelete = viewModel::onDelete,
            onAddSong = { picker.launch(SupportedMimeTypes) },
            modifier = Modifier.padding(padding),
        )
    }

    pendingUri?.let { uri ->
        StorageModeDialog(
            onDismiss = { pendingUri = null },
            onChoose = { mode ->
                viewModel.onImport(uri, mode)
                pendingUri = null
            },
        )
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onDelete: (ProjectWithSource) -> Unit,
    onAddSong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        LibraryUiState.Loading -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }

        LibraryUiState.Empty -> EmptyLibrary(onAddSong = onAddSong, modifier = modifier)

        is LibraryUiState.Content -> Column(modifier = modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Search title, artist, key or tag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            if (state.isImporting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Reading the file…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(ProjectListTestTag),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.projects, key = { it.project.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.project.id) },
                        onDelete = { onDelete(project) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectWithSource,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.project.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = listOfNotNull(project.project.artist, project.project.album)
                        .joinToString(" · ")
                        .ifEmpty { "No artist information" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onOpen, label = { Text(project.project.analysisStatus.label()) })
                    if (project.source?.availability != SourceAvailability.AVAILABLE) {
                        AssistChip(onClick = onOpen, label = { Text("Source unavailable") })
                    }
                    if (project.source?.storageMode == StorageMode.MANAGED_COPY) {
                        AssistChip(onClick = onOpen, label = { Text("Copy stored in app") })
                    }
                }
            }

            IconButton(onClick = { confirmingDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${project.project.title}")
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${project.project.title}?") },
            text = {
                Text(
                    if (project.source?.storageMode == StorageMode.MANAGED_COPY) {
                        "This removes the project, its chart and the copy of the audio this app made. " +
                            "Your original file is untouched."
                    } else {
                        "This removes the project and its chart. Your audio file stays where it is."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun StorageModeDialog(
    onDismiss: () -> Unit,
    onChoose: (StorageMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How should this file be stored?") },
        text = {
            Text(
                "Point at the original and the app keeps permission to read it where it lives. " +
                    "Copy it in and the project keeps working even if you move or delete the original — " +
                    "at the cost of the space.",
            )
        },
        confirmButton = {
            Button(onClick = { onChoose(StorageMode.REFERENCED) }) { Text("Point at original") }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(StorageMode.MANAGED_COPY) }) { Text("Copy into app") }
        },
    )
}

@Composable
private fun EmptyLibrary(onAddSong: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing in the library yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Add a recording you own. Everything stays on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onAddSong) { Text("Add song") }
    }
}

private fun AnalysisStatus.label(): String = when (this) {
    AnalysisStatus.NOT_STARTED -> "Not analyzed"
    AnalysisStatus.QUEUED -> "Queued"
    AnalysisStatus.RUNNING -> "Analyzing"
    AnalysisStatus.PARTIAL -> "Partly analyzed"
    AnalysisStatus.COMPLETE -> "Analyzed"
    AnalysisStatus.FAILED -> "Analysis failed"
}

private suspend fun SnackbarHostState.showMessage(message: LibraryMessage) {
    val text = when (message) {
        is LibraryMessage.Imported -> "Added ${message.title}"
        is LibraryMessage.Duplicate -> "${message.title} is already in your library"
        is LibraryMessage.Failed -> message.reason
        is LibraryMessage.Deleted -> "Deleted ${message.title}"
    }
    showSnackbar(text)
}

private val SupportedMimeTypes = arrayOf("audio/*", "video/*")

internal const val AddSongTestTag = "add-song"
internal const val ProjectListTestTag = "project-list"

/** Named so a test can assert the front page identifies the build it is running. */
const val VersionTestTag = "library-version"
