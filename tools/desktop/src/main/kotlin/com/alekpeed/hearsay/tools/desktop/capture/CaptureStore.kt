package com.alekpeed.hearsay.tools.desktop.capture

import com.alekpeed.hearsay.core.model.music.Chord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One accepted take: what was asked for, and every key that was pressed to answer it. */
@Serializable
data class Capture(
    val id: String,
    val block: String,
    val chord: Chord,
    val voicing: String,
    val inversion: Int,
    val extraIntervals: List<Int>,
    val notes: List<CapturedNote>,
)

@Serializable
data class CapturedNote(val pitch: Int, val velocity: Int, val onMs: Long, val offMs: Long)

/**
 * Where takes are written, and what has already been played.
 *
 * One JSON object per line, appended as each take is accepted. A session that ends in a crash or a
 * closed lid keeps everything up to that point, and the same file is what tells the app where to
 * resume — progress is derived from the takes themselves rather than tracked separately, so the
 * two can never disagree.
 */
class CaptureStore(private val file: Path) {

    private val json = Json { encodeDefaults = true }

    fun append(item: CaptureItem, attempt: ChordAttempt) {
        val capture = Capture(
            id = item.id,
            block = item.block.name,
            chord = item.label,
            voicing = item.voicing.name,
            inversion = item.inversion,
            extraIntervals = item.extraIntervals.sorted(),
            notes = attempt.notes.map { CapturedNote(it.pitch, it.velocity, it.onMs, it.offMs) },
        )
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            file,
            json.encodeToString(capture) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /** The ids already recorded, so a resumed session does not ask for them again. */
    fun completedIds(): Set<String> = readAll().map { it.id }.toSet()

    fun readAll(): List<Capture> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file, StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString<Capture>(line) }.getOrNull() }
    }
}
