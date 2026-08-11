package com.alekpeed.hearsay.tools.analyzer

import com.alekpeed.hearsay.core.audio.AnalysisSettings
import com.alekpeed.hearsay.core.audio.AudioAnalyzer
import com.alekpeed.hearsay.core.audio.dsp.AudioBuffer
import com.alekpeed.hearsay.core.model.export.ChartExporter
import com.alekpeed.hearsay.core.model.export.ExportMetadata
import java.io.File
import kotlin.system.exitProcess

private const val AudioExtensions = "mp3,m4a,aac,flac,wav,ogg,opus,wma,aiff,aif,mp4,webm,mkv"

private val Usage = """
Hearsay desktop analyzer — the same analysis the tablet runs, with a desktop's memory.

    hearsay-analyze [options] <file or folder>...

Options
    --out <folder>        where to write charts        (default: alongside the audio)
    --profile <name>      fast | balanced | maximum    (default: maximum)
    --text                also write a readable lead sheet
    --force               re-analyze files that already have a chart
    --slash-chords        name inversions (C/E) whatever the profile would do
    --no-slash-chords     never name them, likewise
    --help

--slash-chords answers one question: how often does this recording put something other than the
root in the bass? Run a track with and without it and compare. A chart that gains a lot of slash
chords is a recording where taking the bass to be the root would rename many chords wrongly, and
one that gains almost none is a recording where it would be nearly free.

The chart is written as <name>.hearsay.json. Copy it to the tablet and open the song, then
"Import a chart" on the analysis screen.

Maximum quality is the default here because it is the reason to run on a desktop at all: an
8192-point transform over a long recording needs more memory than Android grants an app.
""".trimIndent()

private class Options(
    val inputs: List<File>,
    val outputDirectory: File?,
    val settings: AnalysisSettings,
    val profileName: String,
    val writeText: Boolean,
    val force: Boolean,
)

@Suppress("ReturnCount")
private fun parse(args: Array<String>): Options? {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(Usage)
        return null
    }

    val inputs = mutableListOf<File>()
    var outputDirectory: File? = null
    var profileName = "maximum"
    var writeText = false
    var force = false
    var slashChords: Boolean? = null

    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--out" -> outputDirectory = File(args.getOrElse(++index) { fail("--out needs a folder") })
            "--profile" -> profileName = args.getOrElse(++index) { fail("--profile needs a name") }
            "--text" -> writeText = true
            "--force" -> force = true
            "--slash-chords" -> slashChords = true
            "--no-slash-chords" -> slashChords = false
            else -> {
                if (argument.startsWith("-")) fail("Unknown option: $argument")
                inputs += File(argument)
            }
        }
        index++
    }

    if (inputs.isEmpty()) fail("Nothing to analyze. Pass a file or a folder.")
    inputs.firstOrNull { !it.exists() }?.let { fail("No such file or folder: $it") }

    val profile = when (profileName.lowercase()) {
        "fast" -> AnalysisSettings.Fast
        "balanced" -> AnalysisSettings.Balanced
        "maximum", "max" -> AnalysisSettings.MaximumQuality
        else -> fail("Unknown profile '$profileName'. Use fast, balanced or maximum.")
    }
    // Copied over the profile rather than folded into it, so the profile still means what it says
    // and the only thing this flag changes is whether inversions are named.
    val settings = profile.copy(slashChords = slashChords)

    return Options(inputs, outputDirectory, settings, profileName.lowercase(), writeText, force)
}

private fun fail(message: String): Nothing {
    System.err.println("hearsay-analyze: $message")
    exitProcess(2)
}

/** Every audio file under the given paths, in a stable order so a rerun reports the same list. */
private fun collectAudioFiles(inputs: List<File>): List<File> {
    val extensions = AudioExtensions.split(",").toSet()
    return inputs.flatMap { input ->
        if (input.isDirectory) {
            input.walkTopDown().filter { it.isFile && it.extension.lowercase() in extensions }.toList()
        } else {
            listOf(input)
        }
    }.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }
}

@Suppress("LongMethod")
fun main(args: Array<String>) {
    val options = parse(args) ?: return

    if (!FfmpegDecoder.isAvailable()) {
        System.err.println(
            "hearsay-analyze: ffmpeg is not on the path.\n" +
                "  sudo apt install ffmpeg",
        )
        exitProcess(3)
    }

    val files = collectAudioFiles(options.inputs)
    if (files.isEmpty()) {
        System.err.println("hearsay-analyze: no audio files found.")
        exitProcess(1)
    }

    println("${files.size} file(s), ${options.profileName} profile\n")
    options.outputDirectory?.mkdirs()

    var analyzed = 0
    var skipped = 0
    var failed = 0

    for ((position, file) in files.withIndex()) {
        val destination = chartFileFor(file, options.outputDirectory)
        val label = "[${position + 1}/${files.size}] ${file.name}"

        if (destination.exists() && !options.force) {
            println("$label — already analyzed, skipping (--force to redo)")
            skipped++
            continue
        }

        print("$label … ")
        System.out.flush()

        val startedAt = System.nanoTime()
        val outcome = runCatching { analyze(file, destination, options) }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000.0

        outcome.fold(
            onSuccess = { summary ->
                println(summary.describe(elapsed))
                analyzed++
            },
            onFailure = { error ->
                // Named and counted rather than aborting the batch: one unreadable file in a folder
                // of two hundred should not cost the other hundred and ninety-nine.
                println("failed — ${error.message}")
                failed++
            },
        )
    }

    println("\n$analyzed analyzed, $skipped skipped, $failed failed")
    if (failed > 0 && analyzed == 0) exitProcess(1)
}

private class Summary(val key: String?, val tempo: Float, val chords: Int, val durationSeconds: Double) {
    fun describe(elapsedSeconds: Double): String {
        val speed = if (elapsedSeconds > 0) durationSeconds / elapsedSeconds else 0.0
        return "${key ?: "key unknown"}, ${tempo.toInt()} BPM, $chords chords " +
            "(${"%.1f".format(elapsedSeconds)}s, ${"%.0f".format(speed)}x real time)"
    }
}

/** What one recording produced, before anything is written to disk. */
class Analyzed(val json: String, val text: String, val metadata: ExportMetadata, val chordCount: Int)

/**
 * Everything between decoded samples and the file on disk.
 *
 * Separated from [analyze] so it can be exercised without ffmpeg: this half is the part shared with
 * the tablet, and the part whose output the app has to be able to read back.
 */
fun analyzeSamples(
    samples: FloatArray,
    sampleRate: Int,
    settings: AnalysisSettings,
    title: String,
): Analyzed {
    // Already mono at the analysis rate, so the analyzer's own preparation is a no-op.
    val result = AudioAnalyzer(settings).analyze(samples, channels = 1, sampleRate = sampleRate)

    val metadata = ExportMetadata(
        title = title,
        artist = null,
        keyLabel = result.key?.render(unicodeAccidentals = false),
        tempoBpm = result.tempoBpm,
    )
    return Analyzed(
        json = ChartExporter.toJson(result.chart, metadata),
        text = ChartExporter.toText(result.chart, metadata),
        metadata = metadata,
        chordCount = result.chart.chordEvents.size,
    )
}

private fun analyze(file: File, destination: File, options: Options): Summary {
    val decoded = FfmpegDecoder.decode(file, AudioBuffer.AnalysisSampleRate)
    val analyzed = analyzeSamples(
        samples = decoded.samples,
        sampleRate = decoded.sampleRate,
        settings = options.settings,
        title = file.nameWithoutExtension,
    )

    destination.parentFile?.mkdirs()
    destination.writeText(analyzed.json)
    if (options.writeText) {
        File(destination.parentFile, "${file.nameWithoutExtension}.txt").writeText(analyzed.text)
    }

    return Summary(
        key = analyzed.metadata.keyLabel,
        tempo = analyzed.metadata.tempoBpm ?: 0f,
        chords = analyzed.chordCount,
        durationSeconds = decoded.durationSeconds,
    )
}

private fun chartFileFor(audio: File, outputDirectory: File?): File {
    val name = "${audio.nameWithoutExtension}.hearsay.json"
    return File(outputDirectory ?: audio.parentFile ?: File("."), name)
}
