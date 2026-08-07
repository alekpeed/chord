package com.alekpeed.hearsay.core.data.analysis

import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.database.dao.AnalysisDao
import com.alekpeed.hearsay.core.database.entity.AnalysisJobEntity
import com.alekpeed.hearsay.core.database.entity.AnalysisStageEntity
import com.alekpeed.hearsay.core.database.entity.JobWithStages
import com.alekpeed.hearsay.core.model.analysis.AnalysisFailure
import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.analysis.AnalysisRepository
import com.alekpeed.hearsay.core.model.analysis.AnalysisStage
import com.alekpeed.hearsay.core.model.analysis.JobStatus
import com.alekpeed.hearsay.core.model.analysis.ProcessingBackend
import com.alekpeed.hearsay.core.model.analysis.StageStatus
import com.alekpeed.hearsay.core.model.analysis.StageType
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAnalysisRepository @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val timeProvider: TimeProvider,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : AnalysisRepository {

    override fun observeJob(projectId: String): Flow<AnalysisJob?> =
        analysisDao.observeLatestJob(projectId).map { it?.toDomain() }.flowOn(ioDispatcher)

    override fun observeActiveJobs(): Flow<List<AnalysisJob>> =
        analysisDao.observeActiveJobs().map { jobs -> jobs.map { it.toDomain() } }.flowOn(ioDispatcher)

    override suspend fun latestJob(projectId: String): AnalysisJob? = withContext(ioDispatcher) {
        analysisDao.latestJob(projectId)?.toDomain()
    }

    override suspend fun createJob(projectId: String, profile: AnalysisProfile): AnalysisJob =
        withContext(ioDispatcher) {
            val now = timeProvider.nowMs()
            val jobId = UUID.randomUUID().toString()

            analysisDao.upsertJob(
                AnalysisJobEntity(
                    id = jobId,
                    projectId = projectId,
                    backend = ProcessingBackend.LOCAL.name,
                    profile = profile.name,
                    status = JobStatus.QUEUED.name,
                    createdAtMs = now,
                    startedAtMs = null,
                    completedAtMs = null,
                    progress = 0f,
                    failureCode = null,
                    failureMessage = null,
                ),
            )

            // Every stage is written up front, so the queue screen shows the whole plan from the
            // start rather than revealing it one step at a time.
            analysisDao.upsertStages(
                StageType.Pipeline.mapIndexed { index, type ->
                    AnalysisStageEntity(
                        id = "$jobId:${type.name}",
                        jobId = jobId,
                        stageType = type.name,
                        status = StageStatus.QUEUED.name,
                        orderIndex = index,
                        inputFingerprint = null,
                        outputVersion = 1,
                        progress = 0f,
                        modelId = null,
                        startedAtMs = null,
                        completedAtMs = null,
                        message = null,
                    )
                },
            )

            requireNotNull(analysisDao.job(jobId)).toDomain()
        }

    override suspend fun updateStage(stageId: String, status: StageStatus, progress: Float, message: String?) {
        withContext(ioDispatcher) {
            val now = timeProvider.nowMs()
            val existing = analysisDao.job(stageId.substringBefore(':'))
                ?.stages?.firstOrNull { it.id == stageId } ?: return@withContext
            analysisDao.upsertStage(
                existing.copy(
                    status = status.name,
                    progress = progress.coerceIn(0f, 1f),
                    message = message ?: existing.message,
                    startedAtMs = existing.startedAtMs ?: now.takeIf { status == StageStatus.RUNNING },
                    completedAtMs = if (status == StageStatus.COMPLETE || status == StageStatus.SKIPPED) {
                        now
                    } else {
                        existing.completedAtMs
                    },
                ),
            )
        }
    }

    override suspend fun updateJob(jobId: String, status: JobStatus, progress: Float) {
        withContext(ioDispatcher) {
            val existing = analysisDao.job(jobId)?.job ?: return@withContext
            analysisDao.upsertJob(
                existing.copy(
                    status = status.name,
                    progress = progress.coerceIn(0f, 1f),
                    startedAtMs = existing.startedAtMs ?: timeProvider.nowMs().takeIf {
                        status == JobStatus.RUNNING
                    },
                ),
            )
        }
    }

    override suspend fun finishJob(jobId: String, status: JobStatus, failure: AnalysisFailure?) {
        withContext(ioDispatcher) {
            analysisDao.finishJob(
                jobId = jobId,
                status = status.name,
                completedAtMs = timeProvider.nowMs(),
                failureCode = failure?.code,
                failureMessage = failure?.let(::describe),
            )
        }
    }

    override suspend fun deleteJob(jobId: String) {
        withContext(ioDispatcher) { analysisDao.deleteJob(jobId) }
    }

    /**
     * A job left RUNNING by a killed process is not running. Marking it failed on next launch keeps
     * the queue honest and gives the user a retry rather than a spinner that never resolves.
     */
    override suspend fun recoverOrphanedJobs() {
        withContext(ioDispatcher) {
            for (job in analysisDao.orphanedJobs()) {
                analysisDao.finishJob(
                    jobId = job.id,
                    status = JobStatus.FAILED.name,
                    completedAtMs = timeProvider.nowMs(),
                    failureCode = "INTERRUPTED",
                    failureMessage = "Analysis stopped when the app was closed. Completed stages were kept.",
                )
            }
        }
    }

    private fun describe(failure: AnalysisFailure): String = when (failure) {
        AnalysisFailure.SourceUnavailable ->
            "The audio file could not be opened. Relink it from the library and try again."

        is AnalysisFailure.DecoderUnsupported ->
            "This device cannot decode this file. A WAV or FLAC copy will work."

        AnalysisFailure.OutOfMemory ->
            "The device ran out of memory. Try the Fast profile, or a shorter excerpt."

        AnalysisFailure.Cancelled -> "You cancelled this analysis."
        is AnalysisFailure.Unknown -> failure.message
    }
}

private fun JobWithStages.toDomain(): AnalysisJob = AnalysisJob(
    id = job.id,
    projectId = job.projectId,
    backend = runCatching { ProcessingBackend.valueOf(job.backend) }.getOrDefault(ProcessingBackend.LOCAL),
    profile = runCatching { AnalysisProfile.valueOf(job.profile) }.getOrDefault(AnalysisProfile.BALANCED),
    status = runCatching { JobStatus.valueOf(job.status) }.getOrDefault(JobStatus.QUEUED),
    createdAtMs = job.createdAtMs,
    startedAtMs = job.startedAtMs,
    completedAtMs = job.completedAtMs,
    progress = job.progress,
    failureCode = job.failureCode,
    failureMessage = job.failureMessage,
    stages = stages.sortedBy { it.orderIndex }.map { stage ->
        AnalysisStage(
            id = stage.id,
            jobId = stage.jobId,
            type = runCatching { StageType.valueOf(stage.stageType) }.getOrDefault(StageType.FINALIZE),
            status = runCatching { StageStatus.valueOf(stage.status) }.getOrDefault(StageStatus.QUEUED),
            orderIndex = stage.orderIndex,
            progress = stage.progress,
            inputFingerprint = stage.inputFingerprint,
            message = stage.message,
            startedAtMs = stage.startedAtMs,
            completedAtMs = stage.completedAtMs,
        )
    },
)
