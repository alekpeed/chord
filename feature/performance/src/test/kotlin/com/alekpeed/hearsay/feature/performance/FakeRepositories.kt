package com.alekpeed.hearsay.feature.performance

import com.alekpeed.hearsay.core.model.analysis.AnalysisFailure
import com.alekpeed.hearsay.core.model.analysis.AnalysisJob
import com.alekpeed.hearsay.core.model.analysis.AnalysisLauncher
import com.alekpeed.hearsay.core.model.analysis.AnalysisRepository
import com.alekpeed.hearsay.core.model.analysis.JobStatus
import com.alekpeed.hearsay.core.model.analysis.ProcessingBackend
import com.alekpeed.hearsay.core.model.analysis.StageStatus
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.MediaAsset
import com.alekpeed.hearsay.core.model.project.MediaRole
import com.alekpeed.hearsay.core.model.project.Project
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.Revision
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

fun testProject(
    id: String = "p1",
    durationMs: Long = 16_000,
    availability: SourceAvailability = SourceAvailability.AVAILABLE,
) = ProjectWithSource(
    project = Project(
        id = id,
        title = "Autumn Leaves",
        artist = "Somebody",
        album = null,
        createdAtMs = 0,
        updatedAtMs = 0,
        durationMs = durationMs,
        analysisStatus = AnalysisStatus.COMPLETE,
        activeRevisionId = "r1",
        keyLabel = "C major",
    ),
    source = MediaAsset(
        id = "a1",
        projectId = id,
        role = MediaRole.SOURCE,
        uri = "content://media/1",
        storageMode = StorageMode.REFERENCED,
        mimeType = "audio/flac",
        durationMs = durationMs,
        sampleRate = 44_100,
        channels = 2,
        fileSizeBytes = 1_000,
        checksum = "abc",
        availability = availability,
        displayName = "autumn-leaves.flac",
    ),
)

class FakeProjectRepository(project: ProjectWithSource? = testProject()) : ProjectRepository {
    val projects = MutableStateFlow(listOfNotNull(project))

    override fun observeLibrary(): Flow<List<ProjectWithSource>> = projects
    override fun observeProject(projectId: String): Flow<ProjectWithSource?> =
        projects.map { list -> list.firstOrNull { it.project.id == projectId } }

    override suspend fun getProject(projectId: String): ProjectWithSource? =
        projects.value.firstOrNull { it.project.id == projectId }

    override suspend fun updateMetadata(projectId: String, title: String, artist: String?, album: String?) = Unit
    override suspend fun updateAvailability(mediaAssetId: String, availability: SourceAvailability) = Unit
    override suspend fun relinkSource(projectId: String, uri: String, storageMode: StorageMode) = Unit
    override suspend fun markPracticed(projectId: String, atMs: Long) = Unit
    override suspend fun deleteProject(projectId: String) {
        projects.value = projects.value.filterNot { it.project.id == projectId }
    }
}

class FakeChartRepository(chart: SongChart = SongChart.Empty) : ChartRepository {
    val chart = MutableStateFlow(chart)
    val updates = mutableListOf<Pair<String, Chord?>>()
    var restoreCalls = 0

    override fun observeChart(projectId: String): Flow<SongChart> = this.chart

    override suspend fun revisions(projectId: String): List<Revision> = emptyList()

    override suspend fun replaceChart(
        projectId: String,
        chart: SongChart,
        label: String,
        revisionSourceIsUser: Boolean,
    ): String {
        this.chart.value = chart
        return "r2"
    }

    override suspend fun updateChord(projectId: String, eventId: String, chord: Chord?): String {
        updates += eventId to chord
        return "r2"
    }

    override suspend fun confirmChord(projectId: String, eventId: String, confirmed: Boolean) = Unit

    override suspend fun restoreMachineResult(projectId: String): String? {
        restoreCalls++
        return "r1"
    }

    override suspend fun setActiveRevision(projectId: String, revisionId: String) = Unit

    override fun observeAlternatives(projectId: String) =
        kotlinx.coroutines.flow.MutableStateFlow(alternatives)

    var alternatives: Map<String, List<com.alekpeed.hearsay.core.model.repository.ChordAlternative>> = emptyMap()

    override suspend fun replaceAlternatives(
        projectId: String,
        alternatives: List<com.alekpeed.hearsay.core.model.repository.ChordAlternative>,
    ) {
        this.alternatives = alternatives.groupBy { it.chordEventId }
    }

    override suspend fun splitChordRegion(projectId: String, eventId: String, atMs: Long): String {
        edits += "split:$eventId@$atMs"
        return "r2"
    }

    override suspend fun mergeWithNext(projectId: String, eventId: String): String {
        edits += "merge:$eventId"
        return "r2"
    }

    override suspend fun moveBoundary(projectId: String, eventId: String, newStartMs: Long): String {
        edits += "boundary:$eventId@$newStartMs"
        return "r2"
    }

    override suspend fun renameSection(projectId: String, sectionId: String, label: String): String {
        edits += "section:$sectionId=$label"
        return "r2"
    }

    val edits = mutableListOf<String>()
}

class FakeAnalysisRepository(job: AnalysisJob? = null) : AnalysisRepository {
    val job = MutableStateFlow(job)

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
            stages = emptyList(),
        )
        job.value = created
        return created
    }

    override suspend fun updateStage(stageId: String, status: StageStatus, progress: Float, message: String?) = Unit
    override suspend fun updateJob(jobId: String, status: JobStatus, progress: Float) {
        job.value = job.value?.copy(status = status, progress = progress)
    }

    override suspend fun finishJob(jobId: String, status: JobStatus, failure: AnalysisFailure?) {
        job.value = job.value?.copy(status = status)
    }

    override suspend fun deleteJob(jobId: String) {
        job.value = null
    }

    override suspend fun recoverOrphanedJobs(exceptProjectIds: Set<String>) = Unit
}

/** Records what would have been launched, without a service in the way. */
class FakeAnalysisLauncher : AnalysisLauncher {
    val started = mutableListOf<Pair<String, AnalysisProfile>>()
    val canceled = mutableListOf<String>()

    override fun start(projectId: String, profile: AnalysisProfile) {
        started += projectId to profile
    }

    override fun cancel(projectId: String) {
        canceled += projectId
    }
}
