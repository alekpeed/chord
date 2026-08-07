package com.alekpeed.hearsay.feature.processing

import com.alekpeed.hearsay.core.data.analysis.AnalysisException
import com.alekpeed.hearsay.core.model.analysis.AnalysisFailure
import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.analysis.AnalysisRepository
import com.alekpeed.hearsay.core.model.analysis.AnalysisStage
import com.alekpeed.hearsay.core.model.analysis.JobStatus
import com.alekpeed.hearsay.core.model.analysis.ProcessingBackend
import com.alekpeed.hearsay.core.model.analysis.ProcessingBackendGateway
import com.alekpeed.hearsay.core.model.analysis.StageStatus
import com.alekpeed.hearsay.core.model.analysis.StageType
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What matters here is the bookkeeping around a run, not the analysis itself: a job is recorded
 * before work starts, stage transitions are persisted as they happen, and every way a run can end
 * leaves the database saying something true.
 */

class AnalysisEngineTest {

    private val repository = RecordingAnalysisRepository()

    private fun engine(backend: ProcessingBackendGateway, scope: TestScope) =
        AnalysisEngine(repository, backend, scope)

    @Test
    fun `a successful run is recorded as complete`() = runTest {
        val engine = engine(SucceedingBackend(), this)

        engine.start("p1", AnalysisProfile.BALANCED)
        testScheduler.advanceUntilIdle()

        assertEquals(JobStatus.COMPLETE, repository.job.value?.status)
        assertTrue(repository.stageUpdates.isNotEmpty())
    }

    @Test
    fun `stage transitions reach the database in order`() = runTest {
        val engine = engine(SucceedingBackend(), this)

        engine.start("p1", AnalysisProfile.BALANCED)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(StageType.MEDIA_PREPARATION, StageType.CHORDS),
            repository.stageUpdates.map { it.first },
        )
    }

    @Test
    fun `a failing backend is recorded with the reason the user should see`() = runTest {
        val engine = engine(FailingBackend(AnalysisFailure.SourceUnavailable), this)

        engine.start("p1", AnalysisProfile.BALANCED)
        testScheduler.advanceUntilIdle()

        assertEquals(JobStatus.FAILED, repository.job.value?.status)
        assertEquals("SOURCE_UNAVAILABLE", repository.lastFailure?.code)
    }

    @Test
    fun `cancelling a run marks it cancelled rather than failed`() = runTest {
        val backend = BlockingBackend()
        val engine = engine(backend, this)

        engine.start("p1", AnalysisProfile.BALANCED)
        testScheduler.advanceUntilIdle()
        assertEquals(JobStatus.RUNNING, repository.job.value?.status)

        engine.cancel("p1")
        testScheduler.advanceUntilIdle()

        assertEquals(JobStatus.CANCELLED, repository.job.value?.status)
    }

    @Test
    fun `the set of running projects is exposed while work is in flight`() = runTest {
        val backend = BlockingBackend()
        val engine = engine(backend, this)

        engine.start("p1", AnalysisProfile.BALANCED)
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("p1"), engine.activeProjectIds.value)

        backend.release()
        testScheduler.advanceUntilIdle()
        assertTrue(engine.activeProjectIds.value.isEmpty())
    }

    // ---- doubles -------------------------------------------------------------------------------

    private class SucceedingBackend : ProcessingBackendGateway {
        override val backend = ProcessingBackend.LOCAL
        override val isAvailable = true
        override suspend fun analyze(
            projectId: String,
            jobId: String,
            profile: AnalysisProfile,
            onStage: suspend (StageType, StageStatus, Float, String?) -> Unit,
        ): Result<Unit> {
            onStage(StageType.MEDIA_PREPARATION, StageStatus.COMPLETE, 1f, null)
            onStage(StageType.CHORDS, StageStatus.COMPLETE, 1f, "12 chord regions")
            return Result.success(Unit)
        }
    }

    private class FailingBackend(private val failure: AnalysisFailure) : ProcessingBackendGateway {
        override val backend = ProcessingBackend.LOCAL
        override val isAvailable = true
        override suspend fun analyze(
            projectId: String,
            jobId: String,
            profile: AnalysisProfile,
            onStage: suspend (StageType, StageStatus, Float, String?) -> Unit,
        ): Result<Unit> = Result.failure(AnalysisException(failure))
    }

    /** Blocks until released, so a run can be observed mid-flight and then cancelled. */
    private class BlockingBackend : ProcessingBackendGateway {
        private val gate = CompletableDeferred<Unit>()
        override val backend = ProcessingBackend.LOCAL
        override val isAvailable = true

        fun release() = gate.complete(Unit).let { }

        override suspend fun analyze(
            projectId: String,
            jobId: String,
            profile: AnalysisProfile,
            onStage: suspend (StageType, StageStatus, Float, String?) -> Unit,
        ): Result<Unit> {
            onStage(StageType.MEDIA_PREPARATION, StageStatus.RUNNING, 0f, null)
            gate.await()
            return Result.success(Unit)
        }
    }

    private class RecordingAnalysisRepository : AnalysisRepository {
        val job = MutableStateFlow<AnalysisJob?>(null)
        val stageUpdates = mutableListOf<Triple<StageType, StageStatus, Float>>()
        var lastFailure: AnalysisFailure? = null

        override fun observeJob(projectId: String): Flow<AnalysisJob?> = job
        override fun observeActiveJobs(): Flow<List<AnalysisJob>> =
            job.map { listOfNotNull(it).filter { candidate -> candidate.isActive } }

        override suspend fun latestJob(projectId: String): AnalysisJob? = job.value

        override suspend fun createJob(projectId: String, profile: AnalysisProfile): AnalysisJob {
            val created = AnalysisJob(
                id = "job-1",
                projectId = projectId,
                backend = ProcessingBackend.LOCAL,
                profile = profile,
                status = JobStatus.QUEUED,
                createdAtMs = 0,
                startedAtMs = null,
                completedAtMs = null,
                progress = 0f,
                failureCode = null,
                failureMessage = null,
                stages = StageType.Pipeline.mapIndexed { index, type ->
                    AnalysisStage(
                        id = "job-1:${type.name}",
                        jobId = "job-1",
                        type = type,
                        status = StageStatus.QUEUED,
                        orderIndex = index,
                        progress = 0f,
                        inputFingerprint = null,
                        message = null,
                        startedAtMs = null,
                        completedAtMs = null,
                    )
                },
            )
            job.value = created
            return created
        }

        override suspend fun updateStage(
            stageId: String,
            status: StageStatus,
            progress: Float,
            message: String?,
        ) {
            val type = StageType.valueOf(stageId.substringAfterLast(':'))
            stageUpdates += Triple(type, status, progress)
        }

        override suspend fun updateJob(jobId: String, status: JobStatus, progress: Float) {
            job.value = job.value?.copy(status = status, progress = progress)
        }

        override suspend fun finishJob(jobId: String, status: JobStatus, failure: AnalysisFailure?) {
            lastFailure = failure
            job.value = job.value?.copy(status = status)
        }

        override suspend fun deleteJob(jobId: String) {
            job.value = null
        }

        override suspend fun recoverOrphanedJobs() = Unit
    }
}
