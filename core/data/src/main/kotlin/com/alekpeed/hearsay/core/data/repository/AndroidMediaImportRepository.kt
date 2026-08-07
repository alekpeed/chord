package com.alekpeed.hearsay.core.data.repository

import android.net.Uri
import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.data.mapper.toEntity
import com.alekpeed.hearsay.core.database.dao.ProjectDao
import com.alekpeed.hearsay.core.media.ingest.MediaProbe
import com.alekpeed.hearsay.core.media.ingest.SourceStore
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.MediaAsset
import com.alekpeed.hearsay.core.model.project.MediaRole
import com.alekpeed.hearsay.core.model.project.Project
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.ImportFailure
import com.alekpeed.hearsay.core.model.repository.ImportResult
import com.alekpeed.hearsay.core.model.repository.MediaImportRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings a file picked through the Storage Access Framework into the library.
 *
 * The import deliberately produces no musical analysis. A project starts with a source, a title and
 * an honest [AnalysisStatus.NOT_STARTED]; inventing a chart here would be presenting a guess as a
 * result.
 */
@Singleton
class AndroidMediaImportRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val probe: MediaProbe,
    private val sourceStore: SourceStore,
    private val timeProvider: TimeProvider,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : MediaImportRepository {

    override suspend fun probe(uri: String): MediaAsset? = withContext(ioDispatcher) {
        val probed = probe.probe(Uri.parse(uri)) ?: return@withContext null
        MediaAsset(
            id = "",
            projectId = "",
            role = MediaRole.SOURCE,
            uri = uri,
            storageMode = StorageMode.REFERENCED,
            mimeType = probed.mimeType,
            durationMs = probed.durationMs,
            sampleRate = probed.sampleRate,
            channels = probed.channels,
            fileSizeBytes = probed.fileSizeBytes,
            checksum = null,
            availability = SourceAvailability.AVAILABLE,
            displayName = probed.displayName,
        )
    }

    @Suppress("ReturnCount")
    override suspend fun import(
        uri: String,
        storageMode: StorageMode,
        profile: AnalysisProfile,
    ): ImportResult = withContext(ioDispatcher) {
        val sourceUri = Uri.parse(uri)

        val probed = probe.probe(sourceUri)
            ?: return@withContext ImportResult.Failed(ImportFailure.Unreadable("Could not read this file"))
        if (!probed.hasAudioTrack || probed.durationMs <= 0) {
            return@withContext ImportResult.Failed(ImportFailure.UnsupportedMedia)
        }

        val checksum = probe.checksum(sourceUri)
        checksum?.let { existing ->
            projectDao.findByChecksum(existing)?.let { asset ->
                val title = projectDao.getProject(asset.projectId)?.project?.title.orEmpty()
                return@withContext ImportResult.AlreadyImported(asset.projectId, title)
            }
        }

        val projectId = UUID.randomUUID().toString()
        val storedUri = when (storageMode) {
            StorageMode.REFERENCED -> {
                if (!sourceStore.persistReadPermission(sourceUri)) {
                    return@withContext ImportResult.Failed(ImportFailure.PermissionDenied)
                }
                uri
            }

            StorageMode.MANAGED_COPY -> {
                val required = probed.fileSizeBytes ?: 0L
                if (required > 0 && sourceStore.freeSpaceBytes() < required + StorageHeadroomBytes) {
                    return@withContext ImportResult.Failed(ImportFailure.InsufficientStorage)
                }
                sourceStore.copyIntoManagedStorage(sourceUri, projectId, probed.displayName)?.toString()
                    ?: return@withContext ImportResult.Failed(ImportFailure.Unreadable("Could not copy this file"))
            }
        }

        val now = timeProvider.nowMs()
        val title = probed.title?.takeIf { it.isNotBlank() }
            ?: probed.displayName?.substringBeforeLast('.')
            ?: "Untitled"

        projectDao.upsertProject(
            Project(
                id = projectId,
                title = title,
                artist = probed.artist,
                album = probed.album,
                createdAtMs = now,
                updatedAtMs = now,
                durationMs = probed.durationMs,
                analysisStatus = AnalysisStatus.NOT_STARTED,
                analysisProfile = profile,
                activeRevisionId = null,
            ).toEntity(),
        )

        projectDao.upsertAsset(
            MediaAsset(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                role = MediaRole.SOURCE,
                uri = storedUri,
                storageMode = storageMode,
                mimeType = probed.mimeType,
                durationMs = probed.durationMs,
                sampleRate = probed.sampleRate,
                channels = probed.channels,
                fileSizeBytes = probed.fileSizeBytes,
                checksum = checksum,
                availability = SourceAvailability.AVAILABLE,
                displayName = probed.displayName,
            ).toEntity(),
        )

        ImportResult.Success(projectId)
    }

    private companion object {
        /** Leave room for the database and derived artifacts rather than filling the disk exactly. */
        const val StorageHeadroomBytes = 64L * 1024 * 1024
    }
}
