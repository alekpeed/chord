package com.alekpeed.hearsay.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.alekpeed.hearsay.core.database.entity.AnalysisJobEntity
import com.alekpeed.hearsay.core.database.entity.AnalysisStageEntity
import com.alekpeed.hearsay.core.database.entity.JobWithStages
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {

    @Transaction
    @Query("SELECT * FROM analysis_jobs WHERE projectId = :projectId ORDER BY createdAtMs DESC LIMIT 1")
    fun observeLatestJob(projectId: String): Flow<JobWithStages?>

    @Transaction
    @Query("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED') ORDER BY createdAtMs ASC")
    fun observeActiveJobs(): Flow<List<JobWithStages>>

    @Transaction
    @Query("SELECT * FROM analysis_jobs WHERE id = :jobId")
    suspend fun job(jobId: String): JobWithStages?

    @Transaction
    @Query("SELECT * FROM analysis_jobs WHERE projectId = :projectId ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun latestJob(projectId: String): JobWithStages?

    /**
     * Jobs a dead process left mid-flight; the app recovers these on next launch.
     *
     * QUEUED counts as well as RUNNING. A job is QUEUED for the whole window between its row being
     * written and the first stage starting, so a process that dies in that window — or a bug that
     * cancels the coroutine there — leaves a QUEUED row that nothing will ever advance. Matching
     * only RUNNING left exactly those jobs stuck forever, with the project screen reporting
     * "Starting" and no way out but clearing app data.
     */
    @Query("SELECT * FROM analysis_jobs WHERE status IN ('RUNNING', 'QUEUED')")
    suspend fun orphanedJobs(): List<AnalysisJobEntity>

    @Upsert
    suspend fun upsertJob(job: AnalysisJobEntity)

    @Upsert
    suspend fun upsertStages(stages: List<AnalysisStageEntity>)

    @Upsert
    suspend fun upsertStage(stage: AnalysisStageEntity)

    @Query("UPDATE analysis_jobs SET status = :status, progress = :progress WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String, progress: Float)

    @Query(
        """
        UPDATE analysis_jobs
        SET status = :status, completedAtMs = :completedAtMs, failureCode = :failureCode,
            failureMessage = :failureMessage
        WHERE id = :jobId
        """,
    )
    suspend fun finishJob(
        jobId: String,
        status: String,
        completedAtMs: Long?,
        failureCode: String?,
        failureMessage: String?,
    )

    @Query("UPDATE analysis_stages SET status = :status, progress = :progress WHERE id = :stageId")
    suspend fun updateStage(stageId: String, status: String, progress: Float)

    @Query("DELETE FROM analysis_jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: String)
}
