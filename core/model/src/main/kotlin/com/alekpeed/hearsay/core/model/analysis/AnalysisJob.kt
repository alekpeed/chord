package com.alekpeed.hearsay.core.model.analysis

import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import kotlinx.coroutines.flow.Flow

enum class JobStatus { QUEUED, RUNNING, PAUSED, COMPLETE, FAILED, CANCELLED }

enum class StageStatus { QUEUED, RUNNING, COMPLETE, FAILED, SKIPPED, NEEDS_USER_ACTION }

/**
 * The stages of the pipeline, in the order the specification lists them.
 *
 * Named rather than numbered so a stage can be inserted later without renumbering a user's stored
 * job history, and weighted so the progress bar moves at a rate that reflects real cost.
 */
enum class StageType(val displayName: String, val weight: Float) {
    MEDIA_PREPARATION("Preparing media", 0.10f),
    WAVEFORM("Building the waveform", 0.05f),
    SEPARATION("Separating harmony from percussion", 0.20f),
    RHYTHM("Finding beats and bars", 0.15f),
    TONAL("Finding the key and sections", 0.10f),
    CHORDS("Recognizing chords", 0.25f),
    BASS("Following the bass", 0.10f),
    FINALIZE("Saving the analysis", 0.05f),
    ;

    companion object {
        val Pipeline: List<StageType> = entries
    }
}

/** Where the work runs. Only [LOCAL] exists; the remote case is an interface, not a service. */
enum class ProcessingBackend { LOCAL, REMOTE }

data class AnalysisStage(
    val id: String,
    val jobId: String,
    val type: StageType,
    val status: StageStatus,
    val orderIndex: Int,
    val progress: Float,
    val inputFingerprint: String?,
    val message: String?,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
)

data class AnalysisJob(
    val id: String,
    val projectId: String,
    val backend: ProcessingBackend,
    val profile: AnalysisProfile,
    val status: JobStatus,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val progress: Float,
    val failureCode: String?,
    val failureMessage: String?,
    val stages: List<AnalysisStage>,
) {
    val isActive: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING

    val currentStage: AnalysisStage? get() = stages.firstOrNull { it.status == StageStatus.RUNNING }

    /** Weighted across stages, so the bar does not sit still through the expensive ones. */
    val weightedProgress: Float
        get() {
            if (stages.isEmpty()) return progress
            var total = 0f
            for (stage in stages) {
                total += stage.type.weight * when (stage.status) {
                    StageStatus.COMPLETE, StageStatus.SKIPPED -> 1f
                    StageStatus.RUNNING -> stage.progress
                    else -> 0f
                }
            }
            return total.coerceIn(0f, 1f)
        }
}

/** Why an analysis stopped, in terms the user can act on. */
sealed interface AnalysisFailure {
    data object SourceUnavailable : AnalysisFailure
    data class DecoderUnsupported(val mimeType: String?) : AnalysisFailure
    data object OutOfMemory : AnalysisFailure
    data object Cancelled : AnalysisFailure
    data class Unknown(val message: String) : AnalysisFailure

    val code: String
        get() = when (this) {
            SourceUnavailable -> "SOURCE_UNAVAILABLE"
            is DecoderUnsupported -> "DECODER_UNSUPPORTED"
            OutOfMemory -> "OUT_OF_MEMORY"
            Cancelled -> "CANCELLED"
            is Unknown -> "UNKNOWN"
        }
}

interface AnalysisRepository {
    fun observeJob(projectId: String): Flow<AnalysisJob?>
    fun observeActiveJobs(): Flow<List<AnalysisJob>>

    suspend fun latestJob(projectId: String): AnalysisJob?
    suspend fun createJob(projectId: String, profile: AnalysisProfile): AnalysisJob
    suspend fun updateStage(stageId: String, status: StageStatus, progress: Float, message: String? = null)
    suspend fun updateJob(jobId: String, status: JobStatus, progress: Float)
    suspend fun finishJob(jobId: String, status: JobStatus, failure: AnalysisFailure? = null)
    suspend fun deleteJob(jobId: String)

    /**
     * Marks jobs that a killed process left mid-flight, so the queue screen is never a lie.
     *
     * [exceptProjectIds] are the projects this process is genuinely working on. They have to be
     * excluded, or recovery run while an analysis is in flight would mark a live job failed
     * underneath the coroutine still producing it.
     */
    suspend fun recoverOrphanedJobs(exceptProjectIds: Set<String> = emptySet())
}

/**
 * Where the analysis runs.
 *
 * Only [ProcessingBackend.LOCAL] is implemented. The interface exists so that a remote backend can
 * be added without touching a single feature module — and so that nothing in the app assumes the
 * work happens on this device.
 */
interface ProcessingBackendGateway {
    val backend: ProcessingBackend
    val isAvailable: Boolean

    suspend fun analyze(
        projectId: String,
        jobId: String,
        profile: AnalysisProfile,
        onStage: suspend (StageType, StageStatus, Float, String?) -> Unit,
    ): Result<Unit>
}

/**
 * Starting an analysis, from anywhere in the app.
 *
 * A feature module that wants to kick off a run asks for this rather than reaching into the
 * processing feature — which is what keeps the two from depending on each other, and what lets the
 * whole thing be faked in a test with no service involved.
 */
interface AnalysisLauncher {
    fun start(projectId: String, profile: AnalysisProfile)
    fun cancel(projectId: String)
}
