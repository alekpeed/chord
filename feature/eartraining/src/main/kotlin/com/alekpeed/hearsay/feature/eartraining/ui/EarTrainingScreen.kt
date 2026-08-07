package com.alekpeed.hearsay.feature.eartraining.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alekpeed.hearsay.core.model.eartraining.ExerciseType
import com.alekpeed.hearsay.core.model.eartraining.ListeningMode
import com.alekpeed.hearsay.core.model.eartraining.SessionSummary
import com.alekpeed.hearsay.feature.eartraining.EarTrainingUiState
import com.alekpeed.hearsay.feature.eartraining.EarTrainingViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarTrainingRoute(
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EarTrainingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Ear training") },
                actions = {
                    if (state is EarTrainingUiState.InSession) {
                        TextButton(onClick = viewModel::endSession) { Text("End") }
                    }
                },
            )
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (val current = state) {
            is EarTrainingUiState.NotReady -> NotReadyState(current, content)
            is EarTrainingUiState.Ready -> ReadyState(current, viewModel::startSession, content)
            is EarTrainingUiState.InSession -> QuestionState(
                state = current,
                onAnswer = viewModel::answer,
                onNext = viewModel::next,
                onReplay = viewModel::replay,
                onListeningMode = viewModel::setListeningMode,
                onOpenSource = { onOpenProject(current.exercise.projectId) },
                modifier = content,
            )

            is EarTrainingUiState.Finished -> SummaryState(
                summary = current.summary,
                onDone = viewModel::endSession,
                modifier = content,
            )
        }
    }
}

@Composable
private fun NotReadyState(state: EarTrainingUiState.NotReady, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing to practice yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = state.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 480.dp),
        )
        if (state.history.sessions.isNotEmpty()) {
            SkillBreakdown(state.history, Modifier.padding(top = 24.dp))
        }
    }
}

@Composable
private fun ReadyState(
    state: EarTrainingUiState.Ready,
    onStart: (Set<ExerciseType>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(ExerciseType.entries.toSet()) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Questions are built from your own songs — the actual harmony, in the actual " +
                "recording. Only chords the app is confident about, or that you confirmed, are used.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.history.weakest?.let { weakest ->
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Weakest so far", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = "${weakest.type.displayName} · ${(weakest.accuracy * 100).roundToInt()}% " +
                            "over ${weakest.total} questions",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = { onStart(setOf(weakest.type), 10) }) {
                        Text("Practise just this")
                    }
                }
            }
        }

        Text("What to practice", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ExerciseType.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { type ->
                        FilterChip(
                            selected = type in selected,
                            onClick = {
                                selected = if (type in selected) selected - type else selected + type
                            },
                            label = { Text(type.displayName) },
                        )
                    }
                }
            }
        }

        Button(
            onClick = { onStart(selected, 10) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.testTag(StartSessionTestTag),
        ) {
            Text("Start · 10 questions from ${state.eligibleProjects} song${if (state.eligibleProjects == 1) "" else "s"}")
        }

        SkillBreakdown(state.history)
    }
}

@Composable
private fun QuestionState(
    state: EarTrainingUiState.InSession,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
    onListeningMode: (ListeningMode) -> Unit,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { (state.index + 1f) / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Question ${state.index + 1} of ${state.total} · ${state.correctSoFar} right so far",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(state.exercise.prompt, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "From ${state.exercise.projectTitle}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onReplay) {
                Icon(Icons.Filled.Replay, contentDescription = null)
                Text("  Replay", style = MaterialTheme.typography.labelLarge)
            }
            FilterChip(
                selected = state.listeningMode == ListeningMode.ISOLATED,
                onClick = {
                    onListeningMode(
                        if (state.listeningMode == ListeningMode.ISOLATED) {
                            ListeningMode.FULL_MIX
                        } else {
                            ListeningMode.ISOLATED
                        },
                    )
                },
                enabled = false,
                label = { Text("Isolated") },
            )
        }
        Text(
            text = "Isolated playback needs separated stems, which are not built yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(OptionsTestTag)) {
            state.exercise.options.forEach { option ->
                val isAnswer = option == state.exercise.correctAnswer
                val isChosen = option == state.chosenAnswer
                OutlinedButton(
                    onClick = { onAnswer(option) },
                    enabled = !state.revealed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            state.revealed && isAnswer -> "$option  ✓"
                            state.revealed && isChosen -> "$option  ✗"
                            else -> option
                        },
                        fontWeight = if (state.revealed && isAnswer) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        if (state.revealed) {
            RevealedAnswer(state = state, onNext = onNext, onOpenSource = onOpenSource)
        }
    }
}

/**
 * What the app says once the answer is in.
 *
 * The confidence line and the link back to the chart are the point: a result the user disagrees
 * with should lead them to the evidence, not to an argument.
 */
@Composable
private fun RevealedAnswer(
    state: EarTrainingUiState.InSession,
    onNext: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val wasRight = state.chosenAnswer == state.exercise.correctAnswer
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (wasRight) "Correct" else "Not this time",
            style = MaterialTheme.typography.titleMedium,
            color = if (wasRight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = "The app was ${(state.exercise.confidenceAtGeneration * 100).roundToInt()}% " +
                "confident about this chord when the question was made.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSource) { Text("See it in the chart") }
        Button(onClick = onNext, modifier = Modifier.testTag(NextTestTag)) {
            Text(if (state.index + 1 >= state.total) "Finish" else "Next")
        }
    }
}

@Composable
private fun SummaryState(summary: SessionSummary, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Session finished", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${summary.correct} of ${summary.total} · ${(summary.accuracy * 100).roundToInt()}%",
            style = MaterialTheme.typography.displaySmall,
        )

        Text("By skill", style = MaterialTheme.typography.titleMedium)
        summary.bySkill().forEach { (skill, accuracy) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(skill, style = MaterialTheme.typography.bodyMedium)
                Text("${(accuracy * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun SkillBreakdown(history: EarTrainingUiState.History, modifier: Modifier = Modifier) {
    if (history.skills.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your record", style = MaterialTheme.typography.titleMedium)
        history.skills.sortedBy { it.accuracy }.forEach { skill ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(skill.type.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${(skill.accuracy * 100).roundToInt()}% of ${skill.total}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SessionHistoryList(history: EarTrainingUiState.History, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(history.sessions, key = { it.id }) { session ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${session.total} questions", style = MaterialTheme.typography.bodyMedium)
                Text("${(session.score * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal const val StartSessionTestTag = "start-session"
internal const val OptionsTestTag = "exercise-options"
internal const val NextTestTag = "next-question"
