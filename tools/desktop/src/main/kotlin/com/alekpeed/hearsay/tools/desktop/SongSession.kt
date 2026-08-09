package com.alekpeed.hearsay.tools.desktop

import com.alekpeed.hearsay.core.audio.AnalysisSettings
import com.alekpeed.hearsay.core.audio.AudioAnalyzer
import com.alekpeed.hearsay.core.audio.dsp.AudioBuffer
import com.alekpeed.hearsay.core.model.export.ChartExporter
import com.alekpeed.hearsay.core.model.export.ExportMetadata
import com.alekpeed.hearsay.core.model.timeline.ChartRow
import com.alekpeed.hearsay.core.model.timeline.ChartRowBuilder
import com.alekpeed.hearsay.core.model.timeline.SongChart
import com.alekpeed.hearsay.tools.analyzer.FfmpegDecoder
import java.io.File

/** Which analysis profile a run used, named the way the tablet names them. */
enum class Profile(val label: String, val settings: AnalysisSettings) {
    FAST("Fast", AnalysisSettings.Fast),
    BALANCED("Balanced", AnalysisSettings.Balanced),
    MAXIMUM("Maximum quality", AnalysisSettings.MaximumQuality),
}

/** One analyzed recording: the chart, the rows the table draws, and the audio to play against it. */
class Song(
    val file: File,
    val chart: SongChart,
    val rows: List<ChartRow>,
    val samples: FloatArray,
    val sampleRate: Int,
    val tempoBpm: Float,
    val keyLabel: String?,
    val warnings: List<String>,
) {
    /**
     * The row sounding at [timeMs], or the one most recently sounding.
     *
     * Falling back to the previous row rather than to nothing is deliberate: chord regions do not
     * tile the recording, and a gap that blanked the highlight would read as the very defect this
     * window was built to look for.
     */
    fun rowAt(timeMs: Long): Int {
        if (rows.isEmpty()) return -1
        val exact = rows.indexOfFirst { timeMs >= it.startMs && timeMs < it.endMs }
        if (exact >= 0) return exact
        val previous = rows.indexOfLast { it.startMs <= timeMs }
        return previous
    }

    fun toJson(): String = ChartExporter.toJson(
        chart,
        ExportMetadata(
            title = file.nameWithoutExtension,
            artist = null,
            keyLabel = keyLabel,
            tempoBpm = tempoBpm,
        ),
    )
}

/** Progress from a running analysis, in the form the window shows it. */
data class AnalysisPhase(val label: String, val fraction: Float)

/**
 * Decodes and analyzes a file, off the UI thread.
 *
 * Deliberately not a coroutine scope owned by a screen: an analysis that a window can cancel by
 * being resized or recomposed is the defect already fixed once in the Android app's service, and
 * there is no reason to reintroduce it here.
 */
object SongLoader {

    fun load(file: File, profile: Profile, onProgress: (AnalysisPhase) -> Unit): Song {
        onProgress(AnalysisPhase("Decoding", 0f))
        val decoded = FfmpegDecoder.decode(file, AudioBuffer.AnalysisSampleRate)
        onProgress(AnalysisPhase("Decoding", 1f))

        val result = AudioAnalyzer(profile.settings).analyze(
            interleaved = decoded.samples,
            channels = 1,
            sampleRate = decoded.sampleRate,
        ) { progress ->
            onProgress(AnalysisPhase(progress.stage.displayName, progress.fraction))
        }

        return Song(
            file = file,
            chart = result.chart,
            rows = ChartRowBuilder.build(result.chart),
            samples = decoded.samples,
            sampleRate = decoded.sampleRate,
            tempoBpm = result.tempoBpm,
            keyLabel = result.key?.render(unicodeAccidentals = false),
            warnings = result.warnings,
        )
    }
}
