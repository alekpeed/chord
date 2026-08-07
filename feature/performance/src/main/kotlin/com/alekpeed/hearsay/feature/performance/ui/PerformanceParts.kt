package com.alekpeed.hearsay.feature.performance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.ChartRow
import com.alekpeed.hearsay.core.model.timeline.ChordNotation
import com.alekpeed.hearsay.feature.performance.PerformanceActions
import com.alekpeed.hearsay.feature.performance.PerformanceUiState
import com.alekpeed.hearsay.feature.performance.SourceProblem

@Composable
internal fun PerformanceHeader(
    state: PerformanceUiState.Ready,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.widthIn(max = 320.dp)) {
            state.project.project.artist?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = listOfNotNull(state.keyLabel, state.tempoLabel).joinToString(" · ")
                    .ifEmpty { "Key and tempo not analyzed" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.currentRow?.let { row ->
            Text(
                text = if (state.chordsHidden) "•••" else row.displaySymbol,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun PracticeControls(
    state: PerformanceUiState.Ready,
    actions: PerformanceActions,
    onEditSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedControl(speed = state.playback.speed, onSpeedChange = actions::onSpeedChange)

        TransposeControl(
            semitones = state.display.transposeSemitones,
            onChange = actions::onTransposeChange,
        )

        FilterChip(
            selected = state.autoScroll,
            onClick = { actions.onAutoScrollChange(!state.autoScroll) },
            label = { Text("Follow") },
        )

        FilterChip(
            selected = state.chordsHidden,
            onClick = { actions.onChordsHiddenChange(!state.chordsHidden) },
            label = { Text("Hide chords") },
        )

        FilterChip(
            selected = state.playback.loop != null,
            onClick = { if (state.playback.loop != null) actions.onClearLoop() else actions.onLoopSelectedRow() },
            label = { Text(if (state.playback.loop != null) "Looping" else "Loop bar") },
        )

        NotationControl(state = state, actions = actions)

        if (state.selectedRow != null) {
            AssistChip(onClick = onEditSelected, label = { Text("Edit chord") })
        }
    }
}

@Composable
private fun SpeedControl(speed: Float, onSpeedChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onSpeedChange(speed - SpeedStep) }) { Text("−") }
        Text(text = "%.2f×".format(speed), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { onSpeedChange(speed + SpeedStep) }) { Text("+") }
    }
}

@Composable
private fun TransposeControl(semitones: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onChange(semitones - 1) }) { Text("♭") }
        Text(
            text = if (semitones == 0) "Concert" else "%+d".format(semitones),
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = { onChange(semitones + 1) }) { Text("♯") }
    }
}

@Composable
private fun NotationControl(state: PerformanceUiState.Ready, actions: PerformanceActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ChordNotation.entries.forEach { notation ->
            FilterChip(
                selected = state.display.notation == notation,
                onClick = { actions.onNotationChange(state.display.copy(notation = notation)) },
                label = { Text(notation.label()) },
            )
        }
    }
}

private fun ChordNotation.label(): String = when (this) {
    ChordNotation.SYMBOL -> "Symbols"
    ChordNotation.ROMAN -> "Roman"
    ChordNotation.NASHVILLE -> "Numbers"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChordEditSheet(
    row: ChartRow,
    transposeSemitones: Int,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onConfirm: () -> Unit,
    onRestoreMachineResult: () -> Unit,
) {
    var text by remember(row.eventId) { mutableStateOf(row.displaySymbol) }
    val parsed = remember(text) { if (ChordParser.isNoChord(text)) null else ChordParser.parse(text) }
    val isUnderstood = ChordParser.isNoChord(text) || parsed != null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = row.measureNumber?.let { "Bar $it" } ?: "Chord region",
                style = MaterialTheme.typography.titleLarge,
            )

            Text(
                text = when (row.source) {
                    AnalysisSource.MACHINE -> "Heard by the app · ${(row.confidence * 100).toInt()}% confidence"
                    AnalysisSource.USER -> "Entered by you"
                    AnalysisSource.SEED -> "From a built-in example chart"
                    AnalysisSource.IMPORTED -> "Imported with the project"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Chord symbol") },
                supportingText = {
                    Text(
                        when {
                            !isUnderstood -> "Not a chord symbol this app understands yet"
                            parsed == null -> "Saved as no chord"
                            else -> "Reads as ${ChordFormatter.format(parsed)}"
                        },
                    )
                },
                isError = !isUnderstood,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (transposeSemitones != 0) {
                Text(
                    text = "The table is transposed %+d semitones. What you type is stored at concert pitch."
                        .format(transposeSemitones),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSubmit(text) }, enabled = isUnderstood) { Text("Save") }
                TextButton(onClick = onConfirm) { Text("Mark correct") }
                TextButton(onClick = onRestoreMachineResult) { Text("Restore original") }
            }
        }
    }
}

@Composable
internal fun ManualChartDialog(
    onDismiss: () -> Unit,
    onConfirm: (bpm: Float, beatsPerMeasure: Int) -> Unit,
) {
    var bpmText by remember { mutableStateOf("120") }
    var beatsText by remember { mutableStateOf("4") }
    val bpm = bpmText.toFloatOrNull()
    val beats = beatsText.toIntOrNull()
    val isValid = bpm != null && bpm > 0 && beats != null && beats > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lay out a chart by hand") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This creates an even bar grid you can type chords into. It is your own work, " +
                        "not an analysis, and it is saved as such.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = bpmText,
                    onValueChange = { bpmText = it },
                    label = { Text("Tempo (BPM)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = beatsText,
                    onValueChange = { beatsText = it },
                    label = { Text("Beats per bar") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(bpm ?: 120f, beats ?: 4) },
                enabled = isValid,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NoChartState(
    onCreateManualChart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No chart yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Automatic analysis is not part of this build. You can play the recording now, and lay " +
                "out the bars yourself to start writing changes down.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onCreateManualChart) { Text("Lay out bars") }
    }
}

@Composable
internal fun SourceProblemBanner(problem: SourceProblem, modifier: Modifier = Modifier) {
    val message = when (problem) {
        SourceProblem.MISSING_FILE -> "The audio file for this project could not be found. Relink it from the library."
        SourceProblem.PERMISSION_LOST -> "Android revoked access to this file. Relink it from the library to restore it."
        SourceProblem.DECODER_UNSUPPORTED -> "This device cannot decode this file. Try importing a WAV or FLAC copy."
        SourceProblem.UNKNOWN_PLAYBACK_ERROR -> "Playback stopped unexpectedly. Your analysis and corrections are safe."
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(16.dp),
    )
}

@Composable
internal fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun MessageState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val SpeedStep = 0.05f
