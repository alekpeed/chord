package com.alekpeed.hearsay.feature.capture.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alekpeed.hearsay.core.capture.CaptureItem
import com.alekpeed.hearsay.core.capture.CaptureSession
import com.alekpeed.hearsay.core.capture.CaptureStore
import com.alekpeed.hearsay.core.capture.ChordAttempt
import com.alekpeed.hearsay.core.capture.Curriculum
import com.alekpeed.hearsay.core.capture.GestureDetector
import com.alekpeed.hearsay.core.capture.Verdict
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.feature.capture.MidiInput
import com.alekpeed.hearsay.feature.capture.MidiSource
import java.io.File

private val Sounding = Color(0xFF4CAF50)
private val Wrong = Color(0xFFE57373)

/**
 * Recording a labeled corpus on the tablet, prompt by prompt.
 *
 * The same curriculum and the same acceptance rule as the desktop, from :core:capture — a take
 * recorded on the music stand and one recorded at a computer have to be the same take, or the
 * corpus is two corpora. Only the MIDI plumbing and this screen are Android's.
 */
@Composable
fun CaptureRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val midi = remember { MidiInput(context) }
    val outputFile = remember { File(context.getExternalFilesDir(null), "hearsay-capture/takes.jsonl") }
    val store = remember { CaptureStore(outputFile) }
    val session = remember { CaptureSession(Curriculum.all(), store) }
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

    Column(modifier.fillMaxSize().padding(24.dp)) {
        DeviceBar(midi, connected, outputFile) { source ->
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
        Spacer(Modifier.height(24.dp))

        val current = item
        if (current == null) {
            Finished(session.total, outputFile)
            return@Column
        }

        PromptCard(current)
        Spacer(Modifier.height(20.dp))
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
    outputFile: File,
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
            "Saving to ${outputFile.absolutePath}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PromptCard(item: CaptureItem) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(item.block.title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text(item.prompt(), fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                item.notesFromBottom().joinToString("  "),
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                color = Sounding,
            )
            Spacer(Modifier.height(4.dp))
            Text("lowest note first", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
private fun Finished(total: Int, outputFile: File) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Every chord recorded.", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "$total takes in ${outputFile.absolutePath}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
