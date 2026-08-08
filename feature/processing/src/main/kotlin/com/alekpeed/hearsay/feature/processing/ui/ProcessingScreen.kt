package com.alekpeed.hearsay.feature.processing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.analysis.AnalysisStage
import com.alekpeed.hearsay.core.model.analysis.JobStatus
import com.alekpeed.hearsay.core.model.analysis.StageStatus
import com.alekpeed.hearsay.feature.processing.ProcessingUiState
import com.alekpeed.hearsay.feature.processing.ProcessingViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingRoute(
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProcessingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Processing") }) },
    ) { padding ->
        when (state) {
            is ProcessingUiState.Idle -> IdleState(
                recent = (state as ProcessingUiState.Idle).recent,
                onOpenProject = onOpenProject,
                modifier = Modifier.padding(padding),
            )

            is ProcessingUiState.Working -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items((state as ProcessingUiState.Working).jobs, key = { it.job.id }) { entry ->
                    JobCard(
                        job = entry.job,
                        title = entry.title,
                        onCancel = { viewModel.cancel(entry.job.projectId) },
                        onOpen = { onOpenProject(entry.job.projectId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: AnalysisJob,
    title: String,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = job.statusLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (job.isActive) {
                TextButton(onClick = onCancel) { Text("Stop") }
            } else {
                TextButton(onClick = onOpen) { Text("Open") }
            }
        }

        LinearProgressIndicator(
            progress = { job.weightedProgress },
            modifier = Modifier.fillMaxWidth(),
        )

        job.failureMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Column {
            job.stages.forEach { stage -> StageRow(stage) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun StageRow(stage: AnalysisStage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StageIcon(stage.status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stage.type.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = when (stage.status) {
                    StageStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
                    StageStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            stage.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stage.status.label(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun StageIcon(status: StageStatus) {
    val size = Modifier.size(18.dp)
    when (status) {
        StageStatus.COMPLETE -> Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = size,
        )

        StageStatus.RUNNING -> CircularProgressIndicator(modifier = size, strokeWidth = 2.dp)

        StageStatus.FAILED -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = size,
        )

        StageStatus.SKIPPED -> Icon(
            Icons.Filled.RemoveCircleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = size,
        )

        StageStatus.NEEDS_USER_ACTION -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = size,
        )

        StageStatus.QUEUED -> Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = size,
        )
    }
}

@Composable
private fun IdleState(
    recent: List<ProcessingUiState.Entry>,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recent.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nothing is being analyzed", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Open a project and choose Analyze. Progress will appear here, and keeps " +
                    "running while you use the rest of the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(recent, key = { it.job.id }) { entry ->
            JobCard(
                job = entry.job,
                title = entry.title,
                onCancel = {},
                onOpen = { onOpenProject(entry.job.projectId) },
            )
        }
    }
}

private fun AnalysisJob.statusLine(): String = when (status) {
    JobStatus.QUEUED -> "Waiting to start"
    JobStatus.RUNNING -> "${(weightedProgress * 100).roundToInt()}% · ${currentStage?.type?.displayName ?: "Working"}"
    JobStatus.PAUSED -> "Paused"
    JobStatus.COMPLETE -> "Finished"
    JobStatus.FAILED -> "Stopped"
    JobStatus.CANCELLED -> "Cancelled"
}

private fun StageStatus.label(): String = when (this) {
    StageStatus.QUEUED -> "Queued"
    StageStatus.RUNNING -> "Running"
    StageStatus.COMPLETE -> "Done"
    StageStatus.FAILED -> "Failed"
    StageStatus.SKIPPED -> "Skipped"
    StageStatus.NEEDS_USER_ACTION -> "Needs you"
}
