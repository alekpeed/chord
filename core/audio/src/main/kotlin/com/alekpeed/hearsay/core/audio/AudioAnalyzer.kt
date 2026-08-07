package com.alekpeed.hearsay.core.audio

import com.alekpeed.hearsay.core.audio.dsp.AudioBuffer
import com.alekpeed.hearsay.core.audio.dsp.HarmonicPercussive
import com.alekpeed.hearsay.core.audio.dsp.Spectrogram
import com.alekpeed.hearsay.core.audio.dsp.normalized
import com.alekpeed.hearsay.core.audio.dsp.resampledTo
import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.audio.harmony.ChordRecognizer
import com.alekpeed.hearsay.core.audio.harmony.KeyEstimator
import com.alekpeed.hearsay.core.audio.harmony.RecognizedChord
import com.alekpeed.hearsay.core.audio.harmony.chordChangeStrength
import com.alekpeed.hearsay.core.audio.rhythm.BeatTracker
import com.alekpeed.hearsay.core.audio.rhythm.DownbeatEstimator
import com.alekpeed.hearsay.core.audio.rhythm.OnsetEnvelope
import com.alekpeed.hearsay.core.audio.rhythm.TempoEstimator
import com.alekpeed.hearsay.core.audio.structure.BassTracker
import com.alekpeed.hearsay.core.audio.structure.SectionDetector
import com.alekpeed.hearsay.core.model.music.Key
import com.alekpeed.hearsay.core.model.music.Mode
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SectionEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import com.alekpeed.hearsay.core.model.timeline.TempoSegment

/** How far through the analysis we are, reported so a long job is never a blank wait. */
data class AnalysisProgress(val stage: AnalysisStageId, val fraction: Float)

enum class AnalysisStageId(val displayName: String, val weight: Float) {
    PREPARING("Preparing audio", 0.05f),
    SPECTRUM("Reading the spectrum", 0.20f),
    SEPARATING("Separating harmony from percussion", 0.25f),
    RHYTHM("Finding the beat", 0.15f),
    HARMONY("Recognising chords", 0.20f),
    STRUCTURE("Mapping the form", 0.10f),
    FINALIZING("Finishing up", 0.05f),
}

/** Everything one pass over a recording produces. */
data class AnalysisResult(
    val chart: SongChart,
    val tempoBpm: Float,
    val tempoConfidence: Float,
    val beatsPerMeasure: Int,
    val key: Key?,
    val keyConfidence: Float,
    val chords: List<RecognizedChord>,
    val bassNotes: List<BassTracker.BassNote>,
    val warnings: List<String>,
)

/** Tuning knobs that correspond to the product's Fast / Balanced / Maximum Quality profiles. */
data class AnalysisSettings(
    val separateHarmonicPercussive: Boolean = true,
    val detectSections: Boolean = true,
    val trackBass: Boolean = true,
    val fftSize: Int = Spectrogram.DefaultFftSize,
    val hopSize: Int = Spectrogram.DefaultHopSize,
) {
    companion object {
        val Fast = AnalysisSettings(
            separateHarmonicPercussive = false,
            detectSections = false,
            trackBass = false,
            fftSize = 2048,
            hopSize = 1024,
        )
        val Balanced = AnalysisSettings()
        val MaximumQuality = AnalysisSettings(fftSize = 8192, hopSize = 512)
    }
}

/**
 * The analysis pipeline.
 *
 * Ordered so that each stage feeds the next: percussion is pushed out of the way before harmony is
 * read, beats are found before chords are decided, and chords inform where the bar lines fall. No
 * stage looks ahead at a later one's output, which is what makes the whole thing checkpointable.
 *
 * This is signal processing, not a trained model. It is honest about that: it will read a clean
 * piano trio far better than a dense mix, and the confidence it reports is what the caller should
 * be showing the user.
 */
class AudioAnalyzer(
    private val settings: AnalysisSettings = AnalysisSettings.Balanced,
    private val recognizer: ChordRecognizer = ChordRecognizer(),
) {

    @Suppress("LongMethod") // The pipeline's order is the point of this function; see the phases below.
    fun analyze(
        interleaved: FloatArray,
        channels: Int,
        sampleRate: Int,
        onProgress: (AnalysisProgress) -> Unit = {},
    ): AnalysisResult {
        val warnings = mutableListOf<String>()

        onProgress(AnalysisProgress(AnalysisStageId.PREPARING, 0f))
        val mono = AudioBuffer.mono(interleaved, channels, sampleRate)
            .resampledTo(AudioBuffer.AnalysisSampleRate)
            .normalized()
        if (mono.samples.isEmpty()) {
            return empty("The decoded audio was empty")
        }
        if (mono.durationMs < MinimumDurationMs) {
            warnings += "This recording is very short, so timing and harmony are unreliable."
        }
        onProgress(AnalysisProgress(AnalysisStageId.PREPARING, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.SPECTRUM, 0f))
        val spectrogram = Spectrogram.of(mono, settings.fftSize, settings.hopSize)
        onProgress(AnalysisProgress(AnalysisStageId.SPECTRUM, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.SEPARATING, 0f))
        val split = if (settings.separateHarmonicPercussive) {
            HarmonicPercussive.separate(spectrogram)
        } else {
            HarmonicPercussive.Split(spectrogram.frames, spectrogram.frames)
        }
        onProgress(AnalysisProgress(AnalysisStageId.SEPARATING, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.RHYTHM, 0f))
        val rhythm = analyzeRhythm(spectrogram, split)
        if (rhythm.beatFrames.size < 4) {
            warnings += "No steady pulse was found, so the bar grid is a guess."
        }
        val envelope = rhythm.envelope
        val tempo = rhythm.tempo
        val beatFrames = rhythm.beatFrames
        val beatTimesMs = rhythm.beatTimesMs
        onProgress(AnalysisProgress(AnalysisStageId.RHYTHM, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.HARMONY, 0f))
        val harmony = analyzeHarmony(mono, spectrogram, split, beatTimesMs)
        val chroma = harmony.chroma
        val keyEstimate = harmony.keyEstimate
        val preferFlats = harmony.preferFlats
        val bassBuffer = harmony.bassBuffer
        val chords = harmony.chords
        onProgress(AnalysisProgress(AnalysisStageId.HARMONY, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.STRUCTURE, 0f))
        val structure = analyzeStructure(chroma, envelope, beatFrames, beatTimesMs)
        val beatsPerMeasure = structure.beatsPerMeasure
        val downbeatPhase = structure.downbeatPhase
        val sections = structure.sections
        onProgress(AnalysisProgress(AnalysisStageId.STRUCTURE, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.FINALIZING, 0f))
        val bassNotes = if (settings.trackBass && bassBuffer != null) {
            BassTracker.track(bassBuffer.samples, bassBuffer.sampleRate, beatTimesMs)
        } else {
            emptyList()
        }

        val key = Key(
            tonic = NoteSpelling.fromPitchClass(keyEstimate.tonicPitchClass, preferFlats),
            mode = if (keyEstimate.isMinor) Mode.MINOR else Mode.MAJOR,
        )

        val chart = buildChart(
            chords = chords,
            beatTimesMs = beatTimesMs,
            beatsPerMeasure = beatsPerMeasure,
            downbeatPhase = downbeatPhase,
            sections = sections,
            tempo = tempo.bpm,
            durationMs = mono.durationMs,
            key = key,
        )
        onProgress(AnalysisProgress(AnalysisStageId.FINALIZING, 1f))

        return AnalysisResult(
            chart = chart,
            tempoBpm = tempo.bpm,
            tempoConfidence = tempo.confidence,
            beatsPerMeasure = beatsPerMeasure,
            key = key,
            keyConfidence = keyEstimate.confidence,
            chords = chords,
            bassNotes = bassNotes,
            warnings = warnings,
        )
    }

    private class RhythmAnalysis(
        val envelope: OnsetEnvelope,
        val tempo: com.alekpeed.hearsay.core.audio.rhythm.TempoEstimate,
        val beatFrames: List<Int>,
        val beatTimesMs: List<Long>,
    )

    /** Onsets come from the percussive part, where transients actually live. */
    private fun analyzeRhythm(
        spectrogram: Spectrogram,
        split: HarmonicPercussive.Split,
    ): RhythmAnalysis {
        val envelope = OnsetEnvelope.of(spectrogram, split.percussive)
        val tempo = TempoEstimator.estimate(envelope)
        val beatFrames = BeatTracker.track(envelope, tempo.bpm)
        return RhythmAnalysis(envelope, tempo, beatFrames, beatFrames.map { envelope.timeMsOfFrame(it) })
    }

    private class HarmonyAnalysis(
        val chroma: Chromagram,
        val keyEstimate: KeyEstimator.Estimate,
        val preferFlats: Boolean,
        val bassBuffer: AudioBuffer?,
        val chords: List<RecognizedChord>,
    )

    /** Chroma comes from the harmonic part, where sustained pitch lives. */
    private fun analyzeHarmony(
        mono: AudioBuffer,
        spectrogram: Spectrogram,
        split: HarmonicPercussive.Split,
        beatTimesMs: List<Long>,
    ): HarmonyAnalysis {
        val chroma = Chromagram.of(spectrogram, split.harmonic)
        val keyEstimate = KeyEstimator.estimate(chroma)
        val preferFlats = KeyEstimator.prefersFlats(keyEstimate.tonicPitchClass, keyEstimate.isMinor)

        val bassBuffer = if (settings.trackBass) {
            AudioBuffer(BassTracker.lowPass(mono.samples, mono.sampleRate), mono.sampleRate)
        } else {
            null
        }
        val bassChroma = bassBuffer?.let {
            Chromagram.of(Spectrogram.of(it, settings.fftSize, settings.hopSize))
        }

        val chords = if (beatTimesMs.size >= 2) {
            recognizer.recognize(chroma, beatTimesMs, bassChroma, preferFlats)
        } else {
            emptyList()
        }
        return HarmonyAnalysis(chroma, keyEstimate, preferFlats, bassBuffer, chords)
    }

    private class StructureAnalysis(
        val beatsPerMeasure: Int,
        val downbeatPhase: Int,
        val sections: List<com.alekpeed.hearsay.core.audio.structure.DetectedSection>,
    )

    /** Where the bar lines fall, and where the music stops resembling itself. */
    private fun analyzeStructure(
        chroma: Chromagram,
        envelope: OnsetEnvelope,
        beatFrames: List<Int>,
        beatTimesMs: List<Long>,
    ): StructureAnalysis {
        val changeStrength = chordChangeStrength(chroma, beatTimesMs)
        val beatsPerMeasure = DownbeatEstimator.estimateBeatsPerMeasure(beatFrames, envelope, changeStrength)
        val downbeatPhase = DownbeatEstimator.estimate(beatFrames, envelope, changeStrength, beatsPerMeasure)
        val sections = if (settings.detectSections && beatTimesMs.size >= 2) {
            SectionDetector.detect(chroma, beatTimesMs, beatsPerMeasure)
        } else {
            emptyList()
        }
        return StructureAnalysis(beatsPerMeasure, downbeatPhase, sections)
    }

    /**
     * Merges consecutive spans that decoded to the same chord.
     *
     * Without this the chart is one row per beat, which is unreadable. Merging is done after
     * decoding rather than during it so the per-beat evidence stays intact for the confidence of
     * the merged region — a chord held for eight beats with one weak beat in the middle is more
     * certain than one that squeaked past on a single beat.
     */
    private fun buildChart(
        chords: List<RecognizedChord>,
        beatTimesMs: List<Long>,
        beatsPerMeasure: Int,
        downbeatPhase: Int,
        sections: List<com.alekpeed.hearsay.core.audio.structure.DetectedSection>,
        tempo: Float,
        durationMs: Long,
        key: Key,
    ): SongChart {
        val beats = beatTimesMs.mapIndexed { index, timeMs ->
            val position = (index - downbeatPhase).mod(beatsPerMeasure)
            BeatEvent(
                timeMs = timeMs,
                beatInMeasure = position + 1,
                measureNumber = Math.floorDiv(index - downbeatPhase, beatsPerMeasure) + 1,
                confidence = 0.7f,
                source = AnalysisSource.MACHINE,
            )
        }.filter { it.measureNumber >= 1 }

        val merged = mutableListOf<ChordEvent>()
        var runStart = 0
        var counter = 0

        fun flush(endIndex: Int) {
            if (endIndex <= runStart) return
            val run = chords.subList(runStart, endIndex)
            val first = run.first()
            val averageConfidence = run.map { it.confidence }.average().toFloat()
            // A longer agreeing run is stronger evidence than any single beat in it.
            val lengthBoost = (1f + 0.06f * (run.size - 1)).coerceAtMost(1.35f)
            merged += ChordEvent(
                id = "chord-${counter++}",
                startMs = first.startMs,
                endMs = run.last().endMs,
                chord = first.chord,
                confidence = (averageConfidence * lengthBoost).coerceIn(0f, 1f),
                source = AnalysisSource.MACHINE,
                userConfirmed = false,
            )
        }

        for (index in 1..chords.size) {
            val previous = chords.getOrNull(index - 1)
            val current = chords.getOrNull(index)
            if (current == null || current.chord != previous?.chord) {
                flush(index)
                runStart = index
            }
        }

        val beatMs = if (tempo > 0f) (60_000f / tempo).toLong() else 500L
        val settled = absorbTransitionArtifacts(merged, beatMs)

        val sectionEvents = sections.map { section ->
            SectionEvent(
                id = "section-${section.orderIndex}",
                label = section.label,
                startMs = section.startMs,
                endMs = section.endMs,
                orderIndex = section.orderIndex,
                confidence = section.confidence,
                source = AnalysisSource.MACHINE,
            )
        }

        return SongChart.of(
            chordEvents = settled,
            beats = beats,
            sections = sectionEvents,
            tempoSegments = listOf(TempoSegment(0, maxOf(1, durationMs), tempo, 0.6f)),
            key = key,
        )
    }

    /**
     * Absorbs one-beat regions that are the previous chord bleeding into the next.
     *
     * When a chord is released, its notes decay across the bar line and get counted in the first
     * beat of what follows. An F ringing into a G turns that beat into a G7 — same root, one extra
     * tone, one beat long, sitting right at a boundary. The fix is narrow on purpose: only a short
     * region is absorbed, only into a longer neighbour, and only when they share a root. A genuine
     * short chord with a different root is left exactly where it is, because passing chords being
     * smoothed away is the specific failure this product exists to avoid.
     */
    private fun absorbTransitionArtifacts(events: List<ChordEvent>, beatMs: Long): List<ChordEvent> {
        if (events.size < 2) return events
        val threshold = (beatMs * ArtifactBeatThreshold).toLong()
        val result = mutableListOf<ChordEvent>()
        var carriedStart: Long? = null

        for ((index, event) in events.withIndex()) {
            val previous = result.lastOrNull()
            val next = events.getOrNull(index + 1)
            val isShort = event.durationMs <= threshold && event.chord != null

            if (isShort && absorbs(previous, event)) {
                result[result.lastIndex] = previous!!.copy(endMs = event.endMs)
                continue
            }

            if (isShort && absorbs(next, event)) {
                // Hand the span forward, so the next region starts where this one began.
                carriedStart = carriedStart ?: event.startMs
                continue
            }

            val start = carriedStart ?: event.startMs
            carriedStart = null
            result += if (start != event.startMs) event.copy(startMs = start) else event
        }
        return result
    }

    /** True when [host] is a longer region on the same root, so it can swallow [candidate]. */
    private fun absorbs(host: ChordEvent?, candidate: ChordEvent): Boolean {
        if (host == null) return false
        if (host.chord?.root != candidate.chord?.root) return false
        return host.durationMs > candidate.durationMs
    }

    private fun empty(warning: String) = AnalysisResult(
        chart = SongChart.Empty,
        tempoBpm = 0f,
        tempoConfidence = 0f,
        beatsPerMeasure = 4,
        key = null,
        keyConfidence = 0f,
        chords = emptyList(),
        bassNotes = emptyList(),
        warnings = listOf(warning),
    )

    private companion object {
        const val MinimumDurationMs = 3_000L

        /** A region this short, sharing a root with its neighbour, is decay rather than harmony. */
        const val ArtifactBeatThreshold = 1.3f
    }
}
