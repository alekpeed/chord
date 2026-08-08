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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    fun `canceling a run marks it canceled rather than failed`() = runTest {
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
    fun `enqueue survives the caller that requested it going away`() = runTest {
        // The service starts an analysis and then stops itself, which is normal: it stops as soon
        // as the queue looks empty, and the queue looks empty until the job row lands. If the
        // handover runs in the caller's scope, that cancellation kills the job between being
        // written and being run, and the screen sits on "Starting" forever.
        val engine = engine(SucceedingBackend(), this)
        // Unconfined so the request is actually made before the caller dies — the point is that
        // canceling the caller afterwards does not take the analysis with it.
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        caller.launch { engine.enqueue("p1", AnalysisProfile.BALANCED) }
        caller.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(JobStatus.COMPLETE, repository.job.value?.status)
    }

    @Test
    fun `stopping a job this process never picked up still clears it`() = runTest {
        // The row a killed process left behind. Nothing is running, so cancel has no coroutine to
        // cancel — and if it stops there, the Stop button on the stuck screen does nothing.
        val engine = engine(SucceedingBackend(), this)
        repository.createJob("p1", AnalysisProfile.BALANCED)
        assertTrue(repository.job.value!!.isActive)

        engine.cancel("p1")
        testScheduler.advanceUntilIdle()

        assertEquals(JobStatus.CANCELLED, repository.job.value?.status)
    }

    @Test
    fun `recovery leaves a job this process is actually running alone`() = runTest {
        val backend = BlockingBackend()
        val engine = engine(backend, this)

        engine.start("p1", AnalysisProfile.BALANCED)
        testScheduler.advanceUntilIdle()

        engine.recoverOrphanedJobs()
        testScheduler.advanceUntilIdle()

        assertEquals(JobStatus.RUNNING, repository.job.value?.status)
        assertTrue(repository.recoveredExcluding.contains("p1"))

        backend.release()
        testScheduler.advanceUntilIdle()
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

    /** Blocks until released, so a run can be observed mid-flight and then canceled. */
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
        var recoveredExcluding: Set<String> = emptySet()

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

        override suspend fun recoverOrphanedJobs(exceptProjectIds: Set<String>) {
            recoveredExcluding = exceptProjectIds
            val current = job.value ?: return
            if (current.isActive && current.projectId !in exceptProjectIds) {
                job.value = current.copy(status = JobStatus.FAILED)
            }
        }
    }
}
