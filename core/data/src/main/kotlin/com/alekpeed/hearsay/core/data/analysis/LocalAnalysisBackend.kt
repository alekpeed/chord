package com.alekpeed.hearsay.core.data.analysis

import android.net.Uri
import com.alekpeed.hearsay.core.audio.AnalysisSettings
import com.alekpeed.hearsay.core.audio.AnalysisStageId
import com.alekpeed.hearsay.core.audio.AudioAnalyzer
import com.alekpeed.hearsay.core.audio.dsp.AudioBuffer
import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.data.mapper.toEntity
import com.alekpeed.hearsay.core.database.dao.ProjectDao
import com.alekpeed.hearsay.core.media.ingest.DecodeException
import com.alekpeed.hearsay.core.media.ingest.DecodeFailure
import com.alekpeed.hearsay.core.media.ingest.PcmDecoder
import com.alekpeed.hearsay.core.model.analysis.AnalysisFailure
import com.alekpeed.hearsay.core.model.analysis.ProcessingBackend
import com.alekpeed.hearsay.core.model.analysis.ProcessingBackendGateway
import com.alekpeed.hearsay.core.model.analysis.StageStatus
import com.alekpeed.hearsay.core.model.analysis.StageType
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.ChordAlternative
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the analysis on this device.
 *
 * The pipeline's own stages are mapped onto the persisted [StageType] list, so what the user watches
 * in the processing queue is the same set of steps that actually ran, and each one is checkpointed
 * as it completes. A crash after chord recognition leaves that recorded, even though this version
 * still restarts the run — the record is what a resumable version will read.
 */
@Singleton
class LocalAnalysisBackend @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val chartRepository: ChartRepository,
    private val projectDao: ProjectDao,
    private val decoder: PcmDecoder,
    private val timeProvider: TimeProvider,
    @Dispatcher(HearsayDispatcher.Decode) private val decodeDispatcher: CoroutineDispatcher,
) : ProcessingBackendGateway {

    override val backend: ProcessingBackend = ProcessingBackend.LOCAL
    override val isAvailable: Boolean = true

    // Each early return is a distinct, reportable failure; collapsing them would lose which one.
    @Suppress("ReturnCount", "LongMethod")
    override suspend fun analyze(
        projectId: String,
        jobId: String,
        profile: AnalysisProfile,
        onStage: suspend (StageType, StageStatus, Float, String?) -> Unit,
    ): Result<Unit> = withContext(decodeDispatcher) {
        try {
            onStage(StageType.MEDIA_PREPARATION, StageStatus.RUNNING, 0f, null)

            val project = projectRepository.getProject(projectId)
                ?: return@withContext fail(AnalysisFailure.Unknown("The project no longer exists"))
            val source = project.source?.takeIf { it.availability == SourceAvailability.AVAILABLE }
                ?: run {
                    onStage(StageType.MEDIA_PREPARATION, StageStatus.FAILED, 0f, "Source unavailable")
                    return@withContext fail(AnalysisFailure.SourceUnavailable)
                }

            val decodeProgress = MutableStateFlow(0f)
            val decoded = coroutineScope {
                val reporter = launch {
                    decodeProgress.collect { onStage(StageType.MEDIA_PREPARATION, StageStatus.RUNNING, it, null) }
                }
                // Decoded straight to what the analysis wants. Asking for the file's own rate and
                // channel count and converting afterwards costs several times the memory, all of it
                // at once, which is what exhausted the heap on a real device.
                decoder.decode(
                    uri = Uri.parse(source.uri),
                    targetSampleRate = AudioBuffer.AnalysisSampleRate,
                ) { decodeProgress.value = it }
                    .also { reporter.cancel() }
            }.getOrElse { error ->
                onStage(StageType.MEDIA_PREPARATION, StageStatus.FAILED, 0f, error.message)
                return@withContext fail(error.toAnalysisFailure())
            }

            if (decoded.samples.isEmpty()) {
                onStage(StageType.MEDIA_PREPARATION, StageStatus.FAILED, 0f, "No audio was decoded")
                return@withContext fail(AnalysisFailure.DecoderUnsupported(source.mimeType))
            }
            onStage(StageType.MEDIA_PREPARATION, StageStatus.COMPLETE, 1f, null)

            // The waveform cache is not built yet; skipping it honestly beats reporting it done.
            onStage(StageType.WAVEFORM, StageStatus.SKIPPED, 1f, "Waveform cache is not implemented yet")

            // The analyzer's callback is synchronous and persisting a stage is not, so progress is
            // handed across through a flow that a sibling coroutine drains. Without this the queue
            // screen sits still for the whole run and then jumps to done.
            val analyzer = AudioAnalyzer(profile.toSettings())
            val liveProgress = MutableStateFlow<Pair<StageType, Float>?>(null)

            val result = coroutineScope {
                val reporter = launch {
                    liveProgress.filterNotNull().collect { (stage, fraction) ->
                        onStage(stage, StageStatus.RUNNING, fraction, null)
                    }
                }
                analyzer.analyze(decoded.samples, decoded.channels, decoded.sampleRate) { progress ->
                    liveProgress.value = progress.stage.toStageType() to progress.fraction
                }.also { reporter.cancel() }
            }

            onStage(StageType.SEPARATION, StageStatus.COMPLETE, 1f, null)
            onStage(StageType.RHYTHM, StageStatus.COMPLETE, 1f, "${result.tempoBpm.toInt()} BPM, ${result.beatsPerMeasure}/4")
            onStage(
                StageType.TONAL,
                StageStatus.COMPLETE,
                1f,
                result.key?.render(unicodeAccidentals = false)?.let { "Key of $it" },
            )
            onStage(
                StageType.CHORDS,
                StageStatus.COMPLETE,
                1f,
                "${result.chart.chordEvents.size} chord regions",
            )
            onStage(
                StageType.BASS,
                if (result.bassNotes.isEmpty()) StageStatus.SKIPPED else StageStatus.COMPLETE,
                1f,
                if (result.bassNotes.isEmpty()) "No bass line was clear enough to follow" else null,
            )

            onStage(StageType.FINALIZE, StageStatus.RUNNING, 0f, null)
            if (result.chart.chordEvents.isEmpty()) {
                onStage(StageType.FINALIZE, StageStatus.FAILED, 0f, "Nothing recognizable was found")
                return@withContext fail(
                    AnalysisFailure.Unknown("No harmony could be recognized in this recording"),
                )
            }

            chartRepository.replaceChart(
                projectId = projectId,
                chart = result.chart,
                label = "Analysis",
                revisionSourceIsUser = false,
            )

            // What else the analysis heard. Storing it is the difference between a chart that
            // asserts and a chart the user can argue with.
            persistAlternates(projectId, result)

            projectDao.upsertProject(
                project.project.copy(
                    analysisStatus = AnalysisStatus.COMPLETE,
                    analysisProfile = profile,
                    keyLabel = result.key?.render(unicodeAccidentals = false),
                    tempoBpm = result.tempoBpm,
                    updatedAtMs = timeProvider.nowMs(),
                ).toEntity(),
            )
            onStage(StageType.FINALIZE, StageStatus.COMPLETE, 1f, null)

            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: OutOfMemoryError) {
            fail(AnalysisFailure.OutOfMemory)
        } catch (error: Exception) {
            fail(AnalysisFailure.Unknown(error.message ?: "Analysis failed"))
        }
    }

    /**
     * Attaches each region's runners-up to the row that was actually written.
     *
     * Regions are matched by start time because merging spans changed their identity on the way in;
     * an alternate whose region did not survive the merge is simply dropped.
     */
    private suspend fun persistAlternates(projectId: String, result: com.alekpeed.hearsay.core.audio.AnalysisResult) {
        val chart = chartRepository.observeChart(projectId).first()
        val byStart = chart.chordEvents.associateBy { it.startMs }

        val alternates = result.chords.flatMap { recognized ->
            val event = byStart[recognized.startMs] ?: return@flatMap emptyList()
            recognized.alternates.mapIndexed { index, alternate ->
                ChordAlternative(
                    chordEventId = event.id,
                    rank = index + 1,
                    chord = alternate.chord,
                    displaySymbol = ChordFormatter.format(alternate.chord),
                    confidence = alternate.score.coerceIn(0f, 1f),
                )
            }
        }
        if (alternates.isNotEmpty()) chartRepository.replaceAlternatives(projectId, alternates)
    }

    private fun fail(failure: AnalysisFailure): Result<Unit> = Result.failure(AnalysisException(failure))

    private fun Throwable.toAnalysisFailure(): AnalysisFailure = when {
        this is DecodeException -> when (val reason = failure) {
            DecodeFailure.NoAudioTrack -> AnalysisFailure.DecoderUnsupported(null)
            is DecodeFailure.UnsupportedFormat -> AnalysisFailure.DecoderUnsupported(reason.mimeType)
            is DecodeFailure.Unreadable -> AnalysisFailure.Unknown(reason.message)
        }

        else -> AnalysisFailure.Unknown(message ?: "Decoding failed")
    }

    private fun AnalysisProfile.toSettings(): AnalysisSettings = when (this) {
        AnalysisProfile.FAST -> AnalysisSettings.Fast
        AnalysisProfile.BALANCED -> AnalysisSettings.Balanced
        AnalysisProfile.MAXIMUM_QUALITY -> AnalysisSettings.MaximumQuality
    }

    private fun AnalysisStageId.toStageType(): StageType = when (this) {
        AnalysisStageId.PREPARING -> StageType.MEDIA_PREPARATION
        AnalysisStageId.SPECTRUM, AnalysisStageId.SEPARATING -> StageType.SEPARATION
        AnalysisStageId.RHYTHM -> StageType.RHYTHM
        AnalysisStageId.HARMONY -> StageType.CHORDS
        AnalysisStageId.STRUCTURE -> StageType.TONAL
        AnalysisStageId.FINALIZING -> StageType.FINALIZE
    }
}

class AnalysisException(val failure: AnalysisFailure) : Exception(failure.code)
