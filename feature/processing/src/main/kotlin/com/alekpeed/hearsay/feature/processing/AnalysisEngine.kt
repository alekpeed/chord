package com.alekpeed.hearsay.feature.processing

import com.alekpeed.hearsay.core.common.dispatchers.ApplicationScope
import com.alekpeed.hearsay.core.data.analysis.AnalysisException
import com.alekpeed.hearsay.core.model.analysis.AnalysisFailure
import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.analysis.AnalysisRepository
import com.alekpeed.hearsay.core.model.analysis.JobStatus
import com.alekpeed.hearsay.core.model.analysis.ProcessingBackendGateway
import com.alekpeed.hearsay.core.model.analysis.StageStatus
import com.alekpeed.hearsay.core.model.analysis.StageType
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns running analyses.
 *
 * One place decides what is running, so the service, the queue screen and the project screen all
 * agree. Cancellation is real: the job is canceled, the database says canceled, and the stages
 * that had completed stay completed rather than being rolled back.
 */
@Singleton
class AnalysisEngine @Inject constructor(
    private val analysisRepository: AnalysisRepository,
    private val backend: ProcessingBackendGateway,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val running = ConcurrentHashMap<String, Job>()
    private val _activeProjectIds = MutableStateFlow<Set<String>>(emptySet())
    val activeProjectIds: StateFlow<Set<String>> = _activeProjectIds.asStateFlow()

    fun observeJob(projectId: String): Flow<AnalysisJob?> = analysisRepository.observeJob(projectId)

    fun observeActiveJobs(): Flow<List<AnalysisJob>> = analysisRepository.observeActiveJobs()

    val isBusy: Boolean get() = running.isNotEmpty()

    /**
     * Starts an analysis without tying its creation to the caller's lifetime.
     *
     * [start] suspends while the job row is written, and the service used to call it from its own
     * scope. The service stopped itself the moment it saw an empty queue — which is what the
     * database reports during exactly that window — and canceling its scope killed `start` between
     * writing the row and launching the work. The row existed, so the screen said "Listening to
     * this recording"; no stage ever ran, so it said "Starting"; and it said that forever.
     *
     * Launching on the application scope means nothing shorter-lived can interrupt that handover.
     */
    fun enqueue(projectId: String, profile: AnalysisProfile) {
        scope.launch { start(projectId, profile) }
    }

    /** Starts an analysis, or returns the job already running for this project. */
    suspend fun start(projectId: String, profile: AnalysisProfile): AnalysisJob {
        analysisRepository.latestJob(projectId)?.takeIf { it.isActive && running.containsKey(projectId) }
            ?.let { return it }

        val job = analysisRepository.createJob(projectId, profile)
        val work = scope.launch { run(job, profile) }
        running[projectId] = work
        _activeProjectIds.value = running.keys.toSet()
        work.invokeOnCompletion {
            running.remove(projectId)
            _activeProjectIds.value = running.keys.toSet()
        }
        return job
    }

    fun cancel(projectId: String) {
        val work = running.remove(projectId)
        _activeProjectIds.value = running.keys.toSet()
        work?.cancel()

        // A job with no coroutine behind it is one this process is not running: a row left by a
        // killed process, or one orphaned by a bug. Stop has to resolve those too — otherwise the
        // button the user is looking at, on the screen that is stuck, does nothing at all.
        if (work == null) scope.launch { settleIfStranded(projectId) }
    }

    fun cancelAll() {
        val stranded = running.keys.toSet()
        running.values.forEach { it.cancel() }
        running.clear()
        _activeProjectIds.value = emptySet()
        scope.launch { analysisRepository.recoverOrphanedJobs(exceptProjectIds = stranded) }
    }

    /**
     * Clears jobs left active by a process that is gone.
     *
     * Called at startup, where nothing is running yet, so any active row is a leftover by
     * definition. Jobs this process owns are excluded regardless, because this is also reachable
     * from the queue screen while work is genuinely in flight.
     */
    fun recoverOrphanedJobs() {
        scope.launch { analysisRepository.recoverOrphanedJobs(exceptProjectIds = running.keys.toSet()) }
    }

    private suspend fun settleIfStranded(projectId: String) {
        val job = analysisRepository.latestJob(projectId) ?: return
        if (job.isActive) analysisRepository.finishJob(job.id, JobStatus.CANCELLED, AnalysisFailure.Cancelled)
    }

    private suspend fun run(job: AnalysisJob, profile: AnalysisProfile) {
        analysisRepository.updateJob(job.id, JobStatus.RUNNING, 0f)
        try {
            val result = backend.analyze(job.projectId, job.id, profile) { stage, status, progress, message ->
                analysisRepository.updateStage("${job.id}:${stage.name}", status, progress, message)
                analysisRepository.updateJob(job.id, JobStatus.RUNNING, progressOf(stage, status, progress))
            }

            result.fold(
                onSuccess = { analysisRepository.finishJob(job.id, JobStatus.COMPLETE) },
                onFailure = { error ->
                    val failure = (error as? AnalysisException)?.failure
                        ?: AnalysisFailure.Unknown(error.message ?: "Analysis failed")
                    analysisRepository.finishJob(job.id, JobStatus.FAILED, failure)
                },
            )
        } catch (cancellation: CancellationException) {
            analysisRepository.finishJob(job.id, JobStatus.CANCELLED, AnalysisFailure.Cancelled)
            throw cancellation
        } catch (error: Exception) {
            analysisRepository.finishJob(
                job.id,
                JobStatus.FAILED,
                AnalysisFailure.Unknown(error.message ?: "Analysis failed"),
            )
        }
    }

    /** Cumulative weight of everything finished, plus the current stage's share of its own weight. */
    private fun progressOf(stage: StageType, status: StageStatus, progress: Float): Float {
        var total = 0f
        for (type in StageType.Pipeline) {
            when {
                type.ordinal < stage.ordinal -> total += type.weight
                type == stage -> total += type.weight * if (status == StageStatus.RUNNING) progress else 1f
            }
        }
        return total.coerceIn(0f, 1f)
    }
}
