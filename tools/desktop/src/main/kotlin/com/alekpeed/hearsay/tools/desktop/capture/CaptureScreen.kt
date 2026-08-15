package com.alekpeed.hearsay.tools.desktop.capture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import java.nio.file.Path

private val Sounding = Color(0xFF4CAF50)
private val Wrong = Color(0xFFE57373)

/**
 * Recording a labeled corpus by asking for one chord at a time.
 *
 * The app names what it wants and checks the keys against it, so nothing is labeled by hand and
 * nothing mislabeled can enter the corpus. Wrong notes are not an error state — they are the
 * ordinary case while a hand finds a voicing, so the screen says what is missing and waits.
 */
@Composable
fun CaptureScreen(outputFile: Path, onExit: () -> Unit) {
    val midi = remember { MidiInput() }
    val store = remember(outputFile) { CaptureStore(outputFile) }
    val session = remember(outputFile) { CaptureSession(Curriculum.all(), store) }
    val detector = remember { GestureDetector() }

    var connected by remember { mutableStateOf<MidiSource?>(null) }
    var held by remember { mutableStateOf(emptySet<Int>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var item by remember { mutableStateOf(session.current) }
    var done by remember { mutableStateOf(session.done) }

    DisposableEffect(Unit) { onDispose { midi.close() } }

    fun judge(attempt: ChordAttempt) {
        message = (session.submit(attempt) as? Verdict.Rejected)?.reason
        item = session.current
        done = session.done
    }

    fun connect(source: MidiSource) {
        runCatching {
            midi.open(source) { pitch, velocity, timeMs ->
                if (velocity > 0) detector.noteOn(pitch, velocity, timeMs)
                else detector.noteOff(pitch, timeMs)?.let(::judge)
                held = detector.held()
            }
            connected = source
            message = null
        }.onFailure { message = "Could not open that device: ${it.message}" }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        DeviceBar(midi, connected, outputFile, onExit, ::connect)
        Spacer(Modifier.height(32.dp))

        val current = item
        if (current == null) {
            Finished(session.total, outputFile)
            return@Column
        }

        PromptCard(current)
        Spacer(Modifier.height(24.dp))
        Held(held)
        Spacer(Modifier.height(12.dp))
        message?.let { Text(it, color = Wrong, fontSize = 16.sp) }
        Spacer(Modifier.weight(1f))
        Progress(done, session.total) {
            session.skip()
            item = session.current
            message = null
        }
    }
}

@Composable
private fun DeviceBar(
    midi: MidiInput,
    connected: MidiSource?,
    outputFile: Path,
    onExit: () -> Unit,
    onConnect: (MidiSource) -> Unit,
) {
    var sources by remember { mutableStateOf(emptyList<MidiSource>()) }
    var picking by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { sources = midi.sources(); picking = true }) {
                Text(connected?.name ?: "Choose your piano")
            }
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                if (sources.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No MIDI devices found — plug the piano in and look again") },
                        onClick = { picking = false },
                    )
                }
                for (source in sources) {
                    DropdownMenuItem(
                        text = { Text(source.name) },
                        onClick = {
                            picking = false
                            onConnect(source)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            "Saving to $outputFile",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onExit) { Text("Done") }
    }
}

@Composable
private fun PromptCard(item: CaptureItem) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(item.block.title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text(item.prompt(), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(item.detail(), fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                item.voicing.instruction,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    }
}

/** What the app can hear right now, so a disagreement is visible rather than mysterious. */
@Composable
private fun Held(held: Set<Int>) {
    Text(
        if (held.isEmpty()) "Waiting" else held.sorted().joinToString(" ") { pitch ->
            "${NoteSpelling.fromPitchClass(Math.floorMod(pitch, 12))}${pitch / 12 - 1}"
        },
        fontSize = 22.sp,
        color = if (held.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Sounding,
    )
}

@Composable
private fun Progress(done: Int, total: Int, onSkip: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$done of $total", fontSize = 14.sp)
        Spacer(Modifier.width(16.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Button(onClick = onSkip) { Text("Skip") }
    }
}

@Composable
private fun Finished(total: Int, outputFile: Path) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Every chord recorded.", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("$total takes in $outputFile", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
