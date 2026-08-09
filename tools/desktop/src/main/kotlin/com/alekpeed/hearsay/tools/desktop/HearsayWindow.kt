package com.alekpeed.hearsay.tools.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alekpeed.hearsay.core.model.timeline.ChartRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Dark by default: this is looked at next to a screen showing a recording, usually in a dark room. */
private val Scheme = darkColorScheme()

/** How often the playing position is re-read. Fast enough that a highlight looks continuous. */
private const val TickMs = 30L

@Composable
fun HearsayWindow(initialFile: File?) {
    var song by remember { mutableStateOf<Song?>(null) }
    var phase by remember { mutableStateOf<AnalysisPhase?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf(Profile.BALANCED) }
    var playback by remember { mutableStateOf<Playback?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()

    fun open(file: File) {
        playback?.stop()
        playback = null
        song = null
        failure = null
        positionMs = 0
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching { SongLoader.load(file, profile) { phase = it } }
            }
            phase = null
            outcome.fold(
                onSuccess = {
                    song = it
                    playback = Playback(it.samples, it.sampleRate)
                },
                onFailure = { failure = it.message ?: it.toString() },
            )
        }
    }

    LaunchedEffect(initialFile) { if (initialFile != null) open(initialFile) }

    // One ticker for the whole window rather than a timer per row: the position is the only thing
    // that changes while playing, and everything drawn is a function of it.
    LaunchedEffect(playback) {
        val active = playback ?: return@LaunchedEffect
        while (true) {
            positionMs = active.positionMs
            playing = active.isPlaying
            delay(TickMs)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Scheme.background) {
        MaterialTheme(colorScheme = Scheme) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                TopBar(
                    song = song,
                    profile = profile,
                    busy = phase != null,
                    onProfile = { profile = it },
                    onOpen = { pickFile()?.let(::open) },
                    onReanalyze = { song?.let { open(it.file) } },
                    onExport = { song?.let(::exportChart) },
                )

                Spacer(Modifier.height(12.dp))

                when {
                    phase != null -> Working(phase!!)
                    failure != null -> Failure(failure!!)
                    song == null -> Empty()
                    else -> ChartPane(
                        modifier = Modifier.weight(1f),
                        song = song!!,
                        positionMs = positionMs,
                        follow = follow,
                        onSeek = { ms ->
                            positionMs = ms
                            playback?.seek(ms)
                        },
                    )
                }

                val active = song
                val transport = playback
                if (active != null && transport != null) {
                    Spacer(Modifier.height(12.dp))
                    Transport(
                        positionMs = positionMs,
                        durationMs = transport.durationMs,
                        playing = playing,
                        follow = follow,
                        onToggleFollow = { follow = !follow },
                        onToggle = {
                            transport.toggle()
                            playing = transport.isPlaying
                        },
                        onSeek = { ms ->
                            positionMs = ms
                            transport.seek(ms)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    song: Song?,
    profile: Profile,
    busy: Boolean,
    onProfile: (Profile) -> Unit,
    onOpen: () -> Unit,
    onReanalyze: () -> Unit,
    onExport: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onOpen, enabled = !busy) { Text("Open a recording") }
        Spacer(Modifier.width(8.dp))

        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy) { Text(profile.label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Profile.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onProfile(option)
                            expanded = false
                        },
                    )
                }
            }
        }

        if (song != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onReanalyze, enabled = !busy) { Text("Re-analyze") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onExport, enabled = !busy) { Text("Export chart") }
        }

        Spacer(Modifier.weight(1f))

        if (song != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(song.file.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${song.keyLabel ?: "key unknown"}  ·  ${song.tempoBpm.toInt()} BPM  ·  " +
                        "${song.rows.size} chords",
                    style = MaterialTheme.typography.bodySmall,
                    color = Scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Working(phase: AnalysisPhase) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(phase.label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { phase.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.width(320.dp),
        )
    }
}

@Composable
private fun Failure(message: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("That did not work", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Scheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Decoding needs ffmpeg on the path.",
            color = Scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Empty() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Open a recording to chart it", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "The same analysis the tablet runs, with a desktop's memory — and audio, so you can " +
                "watch the highlight against the music.",
            color = Scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChartPane(
    modifier: Modifier,
    song: Song,
    positionMs: Long,
    follow: Boolean,
    onSeek: (Long) -> Unit,
) {
    val current = song.rowAt(positionMs)
    val state = rememberLazyListState()

    LaunchedEffect(current, follow) {
        if (follow && current >= 0) {
            state.animateScrollToItem(maxOf(0, current - 3))
        }
    }

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Scheme.surface),
    ) {
        LazyColumn(state = state, contentPadding = PaddingValues(8.dp)) {
            itemsIndexed(song.rows) { index, row ->
                ChordRowView(
                    row = row,
                    isCurrent = index == current,
                    onClick = { onSeek(row.startMs) },
                )
            }
        }
    }
}

@Composable
private fun ChordRowView(row: ChartRow, isCurrent: Boolean, onClick: () -> Unit) {
    val background = if (isCurrent) Scheme.primaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(background, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.measureNumber?.let { if (it <= 0) "–" else "$it" } ?: "–",
            modifier = Modifier.width(64.dp),
            color = Scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = formatTime(row.startMs),
            modifier = Modifier.width(88.dp),
            color = Scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = row.displaySymbol,
            modifier = Modifier.weight(1f),
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            fontSize = 20.sp,
            color = if (isCurrent) Scheme.onPrimaryContainer else Scheme.onSurface,
        )
        ConfidenceBar(row.confidence)
    }
}

@Composable
private fun ConfidenceBar(confidence: Float) {
    // Shown rather than summarized: nothing here may present a guess as a fact, and a chord the
    // analysis barely chose should look different from one it was sure of.
    Box(
        Modifier
            .width(72.dp)
            .height(6.dp)
            .background(Scheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(confidence.coerceIn(0f, 1f))
                .height(6.dp)
                .background(
                    when {
                        confidence >= 0.6f -> Scheme.primary
                        confidence >= 0.35f -> Scheme.tertiary
                        else -> Scheme.error
                    },
                    RoundedCornerShape(3.dp),
                ),
        )
    }
}

@Composable
private fun Transport(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    follow: Boolean,
    onToggleFollow: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Column {
        Slider(
            value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            onValueChange = { onSeek((it * durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onToggle) { Text(if (playing) "Pause" else "Play") }
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                fontFamily = FontFamily.Monospace,
                color = Scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onToggleFollow) {
                Text(if (follow) "Following" else "Not following")
            }
        }
    }
}

private fun pickFile(): File? {
    val dialog = FileDialog(null as Frame?, "Open a recording", FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(directory, name)
}

private fun exportChart(song: Song) {
    val dialog = FileDialog(null as Frame?, "Save the chart", FileDialog.SAVE)
    dialog.file = "${song.file.nameWithoutExtension}.hearsay.json"
    dialog.isVisible = true
    val directory = dialog.directory ?: return
    val name = dialog.file ?: return
    File(directory, name).writeText(song.toJson())
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (ms % 1000) / 10
    return "%d:%02d.%02d".format(minutes, seconds, hundredths)
}
