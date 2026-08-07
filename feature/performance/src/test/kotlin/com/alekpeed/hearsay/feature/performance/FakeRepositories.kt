package com.alekpeed.hearsay.feature.performance

import com.alekpeed.hearsay.core.model.music.Chord
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
}
