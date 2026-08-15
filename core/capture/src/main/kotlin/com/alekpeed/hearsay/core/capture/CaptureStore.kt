package com.alekpeed.hearsay.core.capture

import com.alekpeed.hearsay.core.model.music.Chord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
 *
 * Plain [File] rather than the nio equivalents, because the tablet runs this too and half of that
 * API needs a newer Android than the app is willing to require.
 */
class CaptureStore(private val file: File) {

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
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(capture) + "\n", Charsets.UTF_8)
    }

    /** The ids already recorded, so a resumed session does not ask for them again. */
    fun completedIds(): Set<String> = readAll().map { it.id }.toSet()

    fun readAll(): List<Capture> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString<Capture>(line) }.getOrNull() }
    }

    /** Where the takes live, for a screen that has to tell somebody where their work went. */
    val path: String get() = file.absolutePath
}
