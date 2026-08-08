package com.alekpeed.hearsay.core.data.repository

import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.data.mapper.toDomain
import com.alekpeed.hearsay.core.database.dao.ProjectDao
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val timeProvider: TimeProvider,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ProjectRepository {

    override fun observeLibrary(): Flow<List<ProjectWithSource>> =
        projectDao.observeLibrary()
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override fun observeProject(projectId: String): Flow<ProjectWithSource?> =
        projectDao.observeProject(projectId)
            .map { it?.toDomain() }
            .flowOn(ioDispatcher)

    override suspend fun getProject(projectId: String): ProjectWithSource? = withContext(ioDispatcher) {
        projectDao.getProject(projectId)?.toDomain()
    }

    override suspend fun updateMetadata(projectId: String, title: String, artist: String?, album: String?) {
        withContext(ioDispatcher) {
            projectDao.updateMetadata(projectId, title, artist, album, timeProvider.nowMs())
        }
    }

    override suspend fun updateAvailability(mediaAssetId: String, availability: SourceAvailability) {
        withContext(ioDispatcher) { projectDao.updateAvailability(mediaAssetId, availability.name) }
    }

    override suspend fun relinkSource(projectId: String, uri: String, storageMode: StorageMode) {
        withContext(ioDispatcher) {
            val source = projectDao.getProject(projectId)?.toDomain()?.source ?: return@withContext
            projectDao.relinkAsset(source.id, uri, storageMode.name)
        }
    }

    override suspend fun markPracticed(projectId: String, atMs: Long) {
        withContext(ioDispatcher) { projectDao.markPracticed(projectId, atMs) }
    }

    /**
     * Deletes the project row and lets cascading foreign keys take the analysis with it.
     *
     * A referenced source file belongs to the user and is never touched; only a copy this app made
     * for itself is removed, and that is [ManagedSourceCleaner]'s job at the call site.
     */
    override suspend fun deleteProject(projectId: String) {
        withContext(ioDispatcher) { projectDao.deleteProject(projectId) }
    }
}

/** Removes app-owned files left behind by a deleted project. */
fun interface ManagedSourceCleaner {
    suspend fun deleteManagedCopy(uri: String): Boolean
}
