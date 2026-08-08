package com.alekpeed.hearsay.core.data.usecase

import com.alekpeed.hearsay.core.media.ingest.SourceStore
import com.alekpeed.hearsay.core.model.chart.ManualChart
import com.alekpeed.hearsay.core.model.playback.PlaybackRequest
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import javax.inject.Inject

/**
 * Confirms a project's source can still be opened and records what it found.
 *
 * A file the user moved or a permission Android dropped is a recoverable situation, not a broken
 * project, so the result is stored rather than thrown.
 */
class VerifySourceUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val sourceStore: SourceStore,
) {
    suspend operator fun invoke(project: ProjectWithSource): SourceAvailability {
        val source = project.source ?: return SourceAvailability.MISSING
        val availability = sourceStore.availabilityOf(android.net.Uri.parse(source.uri))
        if (availability != source.availability) {
            projectRepository.updateAvailability(source.id, availability)
        }
        return availability
    }
}

/** Turns a project into something the player can open, or null when its source is not usable. */
class BuildPlaybackRequestUseCase @Inject constructor() {
    operator fun invoke(project: ProjectWithSource): PlaybackRequest? {
        val source = project.source?.takeIf { it.availability == SourceAvailability.AVAILABLE } ?: return null
        return PlaybackRequest(
            mediaId = project.project.id,
            uri = source.uri,
            title = project.project.title,
            artist = project.project.artist,
            durationMs = project.project.durationMs,
        )
    }
}

/**
 * Lays a hand-entered beat grid over a project so its chords can be typed in.
 *
 * This is not analysis and does not claim to be: every event it writes is attributed to the user,
 * and the revision it creates is labeled as such.
 */
class CreateManualChartUseCase @Inject constructor(
    private val chartRepository: ChartRepository,
) {
    suspend operator fun invoke(
        project: ProjectWithSource,
        bpm: Float,
        beatsPerMeasure: Int = 4,
        firstDownbeatMs: Long = 0L,
    ): String = chartRepository.replaceChart(
        projectId = project.project.id,
        chart = ManualChart.blankGrid(
            durationMs = project.project.durationMs,
            bpm = bpm,
            beatsPerMeasure = beatsPerMeasure,
            firstDownbeatMs = firstDownbeatMs,
        ),
        label = "Manual chart at ${bpm.toInt()} BPM",
        revisionSourceIsUser = true,
    )
}

/** Deletes a project, and the copy of its audio if — and only if — this app made that copy. */
class DeleteProjectUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val sourceStore: SourceStore,
) {
    suspend operator fun invoke(project: ProjectWithSource) {
        val source = project.source
        projectRepository.deleteProject(project.project.id)
        if (source != null && source.storageMode == StorageMode.MANAGED_COPY) {
            sourceStore.deleteManagedCopy(android.net.Uri.parse(source.uri))
        }
    }
}
