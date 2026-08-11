package com.alekpeed.hearsay.core.audio

import com.alekpeed.hearsay.core.audio.dsp.AudioBuffer
import com.alekpeed.hearsay.core.audio.dsp.HarmonicPercussive
import com.alekpeed.hearsay.core.audio.dsp.Spectrogram
import com.alekpeed.hearsay.core.audio.dsp.normalized
import com.alekpeed.hearsay.core.audio.dsp.resampledTo
import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.audio.harmony.ChordRecognizer
import com.alekpeed.hearsay.core.audio.harmony.HarmonicNovelty
import com.alekpeed.hearsay.core.audio.harmony.KeyContext
import com.alekpeed.hearsay.core.audio.harmony.KeyEstimator
import com.alekpeed.hearsay.core.audio.harmony.RecognizedChord
import com.alekpeed.hearsay.core.audio.harmony.StructuralTransitionGate
import com.alekpeed.hearsay.core.audio.harmony.chordChangeStrength
import com.alekpeed.hearsay.core.audio.harmony.dropUnplayableRegions
import com.alekpeed.hearsay.core.audio.harmony.joinRepeatedRegions
import com.alekpeed.hearsay.core.audio.rhythm.BeatTracker
import com.alekpeed.hearsay.core.audio.rhythm.DownbeatEstimator
import com.alekpeed.hearsay.core.audio.rhythm.LevelEnvelope
import com.alekpeed.hearsay.core.audio.rhythm.MetricalHypothesisEvaluator
import com.alekpeed.hearsay.core.audio.rhythm.OnsetEnvelope
import com.alekpeed.hearsay.core.audio.rhythm.TempoCandidate
import com.alekpeed.hearsay.core.audio.rhythm.TempoCurve
import com.alekpeed.hearsay.core.audio.rhythm.TempoEstimate
import com.alekpeed.hearsay.core.audio.rhythm.TempoEstimator
import com.alekpeed.hearsay.core.audio.rhythm.TrackedBeat
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
    HARMONY("Recognizing chords", 0.20f),
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
    /** The tempos the estimator weighed, winner first — the decision, not just the verdict. */
    val tempoCandidates: List<TempoCandidate> = emptyList(),
)

/**
 * How much harmonic detail the chart should carry.
 *
 * This is a musical judgment, not an accuracy setting. A walking bass under one harmony is one
 * chord to a musician and four chords to a pitch-class matcher; a passing diminished on the second
 * half of beat four belongs in the recording, not on the page. Detail decides which of those the
 * chart is trying to be — and the analysis is equally confident either way.
 *
 * @param slashChords whether a bass note away from the root is named, as G/B.
 * @param extensionPenalty how much a four-note chord has to out-argue a triad to be chosen.
 * @param minimumChordBeats a region shorter than this is absorbed by a neighbor it loses to.
 */
enum class ChartDetail(
    val slashChords: Boolean,
    val extensionPenalty: Float,
    val minimumChordBeats: Float,
) {
    /**
     * A lead sheet. A seventh has to be plainly played, and a chord lasts most of a bar.
     *
     * The cost is real and worth stating: at this setting a genuine Cmaj7 is written C. That is the
     * trade the setting exists to make, not a defect.
     */
    SIMPLE(slashChords = false, extensionPenalty = 0.80f, minimumChordBeats = 2.6f),

    /** Sevenths where they are clearly played, but no chord invented by a moving bass. */
    STANDARD(slashChords = false, extensionPenalty = 0.93f, minimumChordBeats = 1.6f),

    /** Everything the recognizer decided, inversions included. */
    FULL(slashChords = true, extensionPenalty = 1f, minimumChordBeats = 1.3f),
}

/** Tuning knobs that correspond to the product's Fast / Balanced / Maximum Quality profiles. */
data class AnalysisSettings(
    val separateHarmonicPercussive: Boolean = true,
    val detectSections: Boolean = true,
    val trackBass: Boolean = true,
    val fftSize: Int = Spectrogram.DefaultFftSize,
    val hopSize: Int = Spectrogram.DefaultHopSize,
    val detail: ChartDetail = ChartDetail.STANDARD,
    /**
     * Names inversions regardless of what [detail] would choose, or leaves that choice alone when
     * null.
     *
     * A diagnostic rather than a preference. How often a recording inverts is not known for any
     * real library here, and it decides whether treating the bass as the root outright would help
     * or hurt: a chart that comes back full of slash chords is one where that change would rename
     * a great many chords wrongly. Asking the question needs slash chords on without also moving
     * the extension penalty and the minimum chord length, which picking [ChartDetail.FULL] would
     * do — three variables moving at once answers nothing.
     */
    val slashChords: Boolean? = null,
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
    recognizer: ChordRecognizer? = null,
) {

    // Detail is a property of the chart being produced, so the recognizer is built from the same
    // settings rather than handed in already configured and possibly disagreeing with them.
    private val recognizer: ChordRecognizer = recognizer ?: ChordRecognizer(
        slashChords = settings.slashChords ?: settings.detail.slashChords,
        extensionPenalty = settings.detail.extensionPenalty,
    )

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
        // Separation feeds its two outputs straight into the only things that read them — flux for
        // the beat, chroma for the harmony — so neither half is ever held whole. Materializing them
        // costs four more copies of the spectrogram, which is what exhausted memory on a tablet.
        val separated = separateIntoFeatures(spectrogram) { fraction ->
            onProgress(AnalysisProgress(AnalysisStageId.SEPARATING, fraction))
        }
        onProgress(AnalysisProgress(AnalysisStageId.SEPARATING, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.RHYTHM, 0f))
        // Level, not flux: the beat tracker needs to know where the recording is quiet, and flux
        // cannot tell a rest from a chord being held.
        val levels = LevelEnvelope.of(
            samples = mono.samples,
            hopSize = settings.hopSize,
            windowSize = settings.hopSize * LevelWindowHops,
            frameCount = spectrogram.frameCount,
        )
        val rhythm = analyzeRhythm(separated.envelope, separated.chroma, levels)
        if (rhythm.beatFrames.size < 4) {
            warnings += "No steady pulse was found, so the bar grid is a guess."
        }
        val envelope = rhythm.envelope
        val tempo = rhythm.tempo
        val beatFrames = rhythm.beatFrames
        val beatTimesMs = rhythm.beatTimesMs
        onProgress(AnalysisProgress(AnalysisStageId.RHYTHM, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.HARMONY, 0f))
        val harmony = analyzeHarmony(mono, separated.chroma, beatTimesMs)
        val chroma = harmony.chroma
        val keyEstimate = harmony.keyEstimate
        val preferFlats = harmony.preferFlats
        val bassBuffer = harmony.bassBuffer
        val chords = harmony.chords
        onProgress(AnalysisProgress(AnalysisStageId.HARMONY, 1f))

        onProgress(AnalysisProgress(AnalysisStageId.STRUCTURE, 0f))
        val structure = analyzeStructure(chroma, envelope, beatFrames, beatTimesMs, rhythm.beatsPerMeasure)
        val beatsPerMeasure = structure.beatsPerMeasure
        val metricalSpans = structure.metricalSpans
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
            metricalSpans = metricalSpans,
            sections = sections,
            tempo = tempo.bpm,
            beatDetected = rhythm.beatDetected,
            tempoSpans = rhythm.curve.segments(),
            hopSeconds = rhythm.curve.hopSeconds,
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
            tempoCandidates = rhythm.tempoCandidates,
        )
    }

    private class RhythmAnalysis(
        val envelope: OnsetEnvelope,
        val tempo: TempoEstimate,
        val beatFrames: List<Int>,
        val beatTimesMs: List<Long>,
        val curve: TempoCurve,
        val tempoCandidates: List<TempoCandidate>,
        val beatsPerMeasure: Int?,
        /** Per beat, whether the recording actually put anything there. */
        val beatDetected: List<Boolean>,
    )

    private class SeparatedFeatures(val envelope: OnsetEnvelope, val chroma: Chromagram)

    /**
     * Runs the separation, reducing each frame as it appears.
     *
     * Onsets come from the percussive part, where transients actually live; chroma comes from the
     * harmonic part, where sustained pitch lives. Both reductions are tiny — one float and twelve
     * floats per frame — so consuming the separation in step with producing it turns its cost from
     * four spectrogram copies into two scratch arrays.
     */
    private fun separateIntoFeatures(
        spectrogram: Spectrogram,
        onProgress: (Float) -> Unit,
    ): SeparatedFeatures {
        val frameCount = spectrogram.frameCount
        val onsets = OnsetEnvelope.Builder(spectrogram, frameCount)
        val chroma = Chromagram.Builder(spectrogram, frameCount)

        if (!settings.separateHarmonicPercussive) {
            for (frame in spectrogram.frames) {
                onsets.add(frame)
                chroma.add(frame)
            }
            return SeparatedFeatures(onsets.build(), chroma.build())
        }

        val progressStep = maxOf(1, frameCount / ProgressReports)
        HarmonicPercussive.separateInto(spectrogram) { index, harmonic, percussive ->
            onsets.add(percussive)
            chroma.add(harmonic)
            if (index % progressStep == 0) onProgress(index.toFloat() / frameCount)
        }
        return SeparatedFeatures(onsets.build(), chroma.build())
    }

    private fun analyzeRhythm(
        envelope: OnsetEnvelope,
        chroma: Chromagram,
        levels: FloatArray?,
    ): RhythmAnalysis {
        // The curve, not one number. A recording that drifts or opens in free time cannot be
        // followed by a constant period, and the grid sliding out of phase is what makes the
        // playing position move at the wrong speed against the music.
        val (global, candidates) = TempoEstimator.estimateWithCandidates(envelope)
        val selection = MetricalHypothesisEvaluator.select(envelope, chroma, global, candidates)
        val curve = TempoEstimator.curve(envelope, selection.tempo.bpm)
        val tracked = BeatTracker.trackDetailed(envelope, curve, levels)
        val beatFrames = tracked.map(TrackedBeat::frame)
        val tempo = TempoEstimate(
            curve.medianBpm.takeIf { it > 0f } ?: selection.tempo.bpm,
            selection.tempo.confidence,
        )
        return RhythmAnalysis(
            beatDetected = tracked.map(TrackedBeat::detected),
            envelope = envelope,
            tempo = tempo,
            beatFrames = beatFrames,
            beatTimesMs = beatFrames.map { envelope.timeMsOfFrame(it) },
            curve = curve,
            tempoCandidates = selection.candidates,
            beatsPerMeasure = selection.winner?.beatsPerMeasure,
        )
    }

    /** Chroma comes from the harmonic part, where sustained pitch lives. */
    private class HarmonyAnalysis(
        val chroma: Chromagram,
        val keyEstimate: KeyEstimator.Estimate,
        val preferFlats: Boolean,
        val bassBuffer: AudioBuffer?,
        val chords: List<RecognizedChord>,
    )

    private fun analyzeHarmony(
        mono: AudioBuffer,
        chroma: Chromagram,
        beatTimesMs: List<Long>,
    ): HarmonyAnalysis {
        val keyEstimate = KeyEstimator.estimate(chroma)
        val preferFlats = KeyEstimator.prefersFlats(keyEstimate.tonicPitchClass, keyEstimate.isMinor)

        val bassBuffer = if (settings.trackBass) {
            AudioBuffer(BassTracker.lowPass(mono.samples, mono.sampleRate), mono.sampleRate)
        } else {
            null
        }
        // Transformed and folded frame by frame. Materializing this second spectrogram cost as much
        // as the first one and nothing but the chroma was ever read from it.
        val bassChroma = bassBuffer?.let { buffer ->
            val builder = Chromagram.Builder(
                binCount = Spectrogram.binCountFor(settings.fftSize),
                sampleRate = buffer.sampleRate,
                fftSize = settings.fftSize,
                hopSeconds = settings.hopSize.toDouble() / buffer.sampleRate,
                expectedFrames = Spectrogram.frameCountFor(buffer, settings.hopSize),
            )
            Spectrogram.forEachFrame(buffer, settings.fftSize, settings.hopSize) { _, magnitudes ->
                builder.add(magnitudes)
            }
            builder.build()
        }

        // Where the harmony turns over, decided from the audio and not from the labels. The beat
        // grid alone cannot represent a change that falls between beats, and the decoder alone
        // cannot report one it was too unsure to commit to; between them those are most of the
        // changes a player actually watches for.
        val novelty = HarmonicNovelty.of(chroma)
        val changeTimesMs = HarmonicNovelty.peaks(novelty, chroma.hopSeconds)
            .map { chroma.timeMsOfFrame(it) }
        val boundaries = mergeBoundaries(beatTimesMs, changeTimesMs, chroma.hopSeconds)

        val chords = if (boundaries.size >= 2) {
            recognizer.recognize(
                chroma = chroma,
                beatTimesMs = boundaries,
                bassChroma = bassChroma,
                preferFlats = preferFlats,
                key = KeyContext(
                    tonicPitchClass = keyEstimate.tonicPitchClass,
                    isMinor = keyEstimate.isMinor,
                    confidence = keyEstimate.confidence,
                ),
                changeLikelihood = spanChangeLikelihood(boundaries, changeTimesMs, novelty, chroma),
                // The real beat, not the analysis spans: boundaries also carry detected harmonic
                // changes, so their spacing is not a tempo. How long a chord must hold to be
                // believed is a musical duration, and this is what makes it one.
                beatMs = medianBeatMs(beatTimesMs),
            )
        } else {
            emptyList()
        }
        return HarmonyAnalysis(chroma, keyEstimate, preferFlats, bassBuffer, chords)
    }

    /** The beat grid's own median spacing, or 120 BPM when there is no usable grid. */
    private fun medianBeatMs(beatTimesMs: List<Long>): Long {
        val spacings = beatTimesMs.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (spacings.isEmpty()) return StructuralTransitionGate.DefaultBeatMs
        return spacings[spacings.size / 2]
    }

    /**
     * Beats and detected changes together, as the places a chord is allowed to start.
     *
     * Both, rather than either. Detected changes carry the timing the grid cannot express; the
     * grid carries the changes the detector missed, so adding one never costs what the other
     * already found. Two boundaries closer together than a couple of frames are one boundary, and
     * the detected one wins — it is the one measured from the audio.
     */
    private fun mergeBoundaries(beatTimesMs: List<Long>, changeTimesMs: List<Long>, hopSeconds: Double): List<Long> {
        if (changeTimesMs.isEmpty()) return beatTimesMs
        val tolerance = (hopSeconds * 1000 * BoundaryMergeHops).toLong().coerceAtLeast(1)
        val out = mutableListOf<Long>()
        for (time in (beatTimesMs + changeTimesMs).sorted()) {
            val last = out.lastOrNull()
            if (last == null || time - last > tolerance) {
                out += time
            } else if (time in changeTimesMs && last !in changeTimesMs) {
                out[out.lastIndex] = time
            }
        }
        return out
    }

    /**
     * How much each span's start looks like a real change, for the decoder to weigh.
     *
     * Only detected change points count, not the raw novelty under every boundary. Reading novelty
     * everywhere relaxes the decoder's stickiness a little at every beat, and a little everywhere
     * is enough to let it flicker to a related chord mid-phrase — measured: a spurious F appearing
     * inside a Dm7 that had been read correctly before. A change point is a claim about one moment
     * and the relief belongs at that moment alone.
     *
     * Raising the stickiness at every *non*-change point was tried too, to attack the opposite
     * failure — four chords reported in a bar of one. It was measured and it made segmentation
     * worse rather than better, twelve chords where a fixture played eight, against eleven without
     * it. Removed rather than kept and hoped over.
     */
    private fun spanChangeLikelihood(
        boundaries: List<Long>,
        changeTimesMs: List<Long>,
        novelty: FloatArray,
        chroma: Chromagram,
    ): FloatArray {
        val out = FloatArray(maxOf(0, boundaries.size - 1))
        if (changeTimesMs.isEmpty()) return out
        val peak = novelty.maxOrNull() ?: 0f
        if (peak <= 0f) return out

        val changes = changeTimesMs.toHashSet()
        for (index in out.indices) {
            if (boundaries[index] !in changes) continue
            val frame = ((boundaries[index] / 1000.0) / chroma.hopSeconds).toInt()
                .coerceIn(0, maxOf(0, novelty.size - 1))
            out[index] = (novelty[frame] / peak).coerceIn(0f, 1f)
        }
        return out
    }

    private class StructureAnalysis(
        val beatsPerMeasure: Int,
        val metricalSpans: List<DownbeatEstimator.MetricalSpan>,
        val sections: List<com.alekpeed.hearsay.core.audio.structure.DetectedSection>,
    ) {
        /** The opening phase, which is what the result reports as "the" downbeat. */
        val downbeatPhase: Int get() = metricalSpans.firstOrNull()?.phase ?: 0
    }

    /** Where the bar lines fall, and where the music stops resembling itself. */
    private fun analyzeStructure(
        chroma: Chromagram,
        envelope: OnsetEnvelope,
        beatFrames: List<Int>,
        beatTimesMs: List<Long>,
        selectedBeatsPerMeasure: Int?,
    ): StructureAnalysis {
        val changeStrength = chordChangeStrength(chroma, beatTimesMs)
        val beatsPerMeasure = selectedBeatsPerMeasure
            ?: DownbeatEstimator.estimateBeatsPerMeasure(beatFrames, envelope, changeStrength)
        val metricalSpans =
            DownbeatEstimator.spans(beatFrames, beatTimesMs, envelope, changeStrength, beatsPerMeasure)
        val sections = if (settings.detectSections && beatTimesMs.size >= 2) {
            SectionDetector.detect(chroma, beatTimesMs, beatsPerMeasure)
        } else {
            emptyList()
        }
        return StructureAnalysis(beatsPerMeasure, metricalSpans, sections)
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
        metricalSpans: List<DownbeatEstimator.MetricalSpan>,
        sections: List<com.alekpeed.hearsay.core.audio.structure.DetectedSection>,
        tempo: Float,
        beatDetected: List<Boolean>,
        tempoSpans: List<com.alekpeed.hearsay.core.audio.rhythm.TempoSpan>,
        hopSeconds: Double,
        durationMs: Long,
        key: Key,
    ): SongChart {
        // Beats before the first downbeat are a pickup, numbered bar zero. They used to be dropped
        // for being "before bar one", which left everything that happens before the first downbeat
        // with no beat under it at all: no bar number on the chart, and nothing for the playing
        // position to follow. Music that starts before its first downbeat is normal, not an error.
        //
        // The bar count runs across the whole chart, but each stretch of continuous music sets its
        // own bar line. A rest lasting a fraction of a bar would otherwise rotate everything on one
        // side of it, and it is the shorter side that loses — usually the intro, whose opening
        // chord then reads as a pickup belonging to no bar at all.
        val beats = ArrayList<BeatEvent>(beatTimesMs.size)
        var nextBar = 1
        for ((spanIndex, span) in metricalSpans.withIndex()) {
            // A stretch that opens with a pickup needs a bar number for it. The first one may use
            // bar zero, which the chart renders as no number; a later one cannot, because that
            // number already belongs to the music before the rest.
            if (spanIndex > 0 && span.phase > 0) nextBar++
            for (index in span.from until span.to) {
                val local = index - span.from - span.phase
                beats += BeatEvent(
                    timeMs = beatTimesMs[index],
                    beatInMeasure = local.mod(beatsPerMeasure) + 1,
                    measureNumber = nextBar + Math.floorDiv(local, beatsPerMeasure),
                    // A beat the tracker filled in was inferred from the beats either side of it,
                    // not heard. Reporting it at the same confidence as a detected beat would
                    // present an inference as an observation.
                    confidence = if (beatDetected.getOrElse(index) { true }) {
                        DetectedBeatConfidence
                    } else {
                        FilledBeatConfidence
                    },
                    source = AnalysisSource.MACHINE,
                )
            }
            nextBar = (beats.lastOrNull()?.measureNumber ?: nextBar) + 1
        }

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
        // Order matters. Regions too short to be harmony go first, because one of them sitting
        // between two readings of the same chord is what hides that they were one chord; joining
        // then collapses what it was hiding, and only what survives is worth weighing for decay
        // bleeding across a bar line.
        val settled = absorbTransitionArtifacts(
            joinRepeatedRegions(dropUnplayableRegions(mergeBassMovement(merged), beatMs)),
            beatMs,
        )

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
            // The tempo the analysis actually found, span by span. One segment across the whole
            // recording was a claim the analysis was never in a position to make.
            tempoSegments = tempoSpans
                .map { span ->
                    TempoSegment(
                        startMs = (span.startFrame * hopSeconds * 1000).toLong(),
                        endMs = (span.endFrame * hopSeconds * 1000).toLong().coerceAtLeast(1),
                        bpm = span.bpm,
                        confidence = 0.6f,
                    )
                }
                .filter { it.endMs > it.startMs }
                .ifEmpty { listOf(TempoSegment(0, maxOf(1, durationMs), tempo, 0.6f)) },
            key = key,
        )
    }

    /**
     * Absorbs one-beat regions that are the previous chord bleeding into the next.
     *
     * When a chord is released, its notes decay across the bar line and get counted in the first
     * beat of what follows. An F ringing into a G turns that beat into a G7 — same root, one extra
     * tone, one beat long, sitting right at a boundary. The fix is narrow on purpose: only a short
     * region is absorbed, only into a longer neighbor, and only when they share a root. A genuine
     * short chord with a different root is left exactly where it is, because passing chords being
     * smoothed away is the specific failure this product exists to avoid.
     */
    private fun absorbTransitionArtifacts(events: List<ChordEvent>, beatMs: Long): List<ChordEvent> {
        if (events.size < 2) return events
        val threshold = (beatMs * settings.detail.minimumChordBeats).toLong()
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

    /**
     * True when [host] is a longer region on the same root, so it can swallow [candidate].
     *
     * Same root only, deliberately. Allowing a louder neighbor on a different root to absorb a
     * short region does remove chords from the chart, but it removes them from the front of the
     * next bar: the following chord then starts late and no longer lands on the bar line, which is
     * harder to read than the extra chord was. Over-segmentation is dealt with before this point,
     * by not letting a moving bass invent chords in the first place.
     */
    private fun absorbs(host: ChordEvent?, candidate: ChordEvent): Boolean {
        if (host == null) return false
        if (host.chord?.root != candidate.chord?.root) return false
        return host.durationMs > candidate.durationMs
    }

    /**
     * Merges neighbors that are the same chord under a moving bass.
     *
     * A walking bass under one harmony is one chord to a musician. Left alone it becomes G, G/B,
     * G/D — three rows saying the same thing, which is most of what makes a chart unreadable. The
     * bass note of the merged region is the one it started on, because that is the chord's own.
     */
    private fun mergeBassMovement(events: List<ChordEvent>): List<ChordEvent> {
        if (events.size < 2) return events
        val result = mutableListOf<ChordEvent>()
        for (event in events) {
            val previous = result.lastOrNull()
            val previousChord = previous?.chord
            val currentChord = event.chord
            val sameHarmony = previousChord != null && currentChord != null &&
                previousChord.copy(bass = null) == currentChord.copy(bass = null)
            if (sameHarmony) {
                result[result.lastIndex] = previous!!.copy(
                    endMs = event.endMs,
                    confidence = maxOf(previous.confidence, event.confidence),
                )
            } else {
                result += event
            }
        }
        return result
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

        /** How often the separation reports progress; it is by far the longest stage. */
        const val ProgressReports = 50

        /** A beat the tracker found in the recording. */
        const val DetectedBeatConfidence = 0.7f

        /** A beat inferred to bridge a gap the tracker could not fill from evidence. */
        const val FilledBeatConfidence = 0.35f

        /**
         * How wide the level window is, in hops.
         *
         * Wide enough that one sample crossing zero is not read as silence, narrow enough that the
         * edge of a pause is not smeared into the bar beside it. At the default hop that is 93 ms.
         */
        const val LevelWindowHops = 4

        /** Boundaries within this many frames of each other describe the same moment. */
        const val BoundaryMergeHops = 2

        /** A region this short, sharing a root with its neighbor, is decay rather than harmony. */
    }
}
