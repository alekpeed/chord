package com.alekpeed.hearsay.core.data.repository

import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.data.mapper.toDomain
import com.alekpeed.hearsay.core.data.mapper.toEntity
import com.alekpeed.hearsay.core.database.dao.PracticeDao
import com.alekpeed.hearsay.core.model.repository.PracticeRepository
import com.alekpeed.hearsay.core.model.repository.SavedLoop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPracticeRepository @Inject constructor(
    private val practiceDao: PracticeDao,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : PracticeRepository {

    override fun observeLoops(projectId: String): Flow<List<SavedLoop>> =
        practiceDao.observeLoops(projectId)
            .map { loops -> loops.map { it.toDomain() } }
            .flowOn(ioDispatcher)

    override suspend fun saveLoop(loop: SavedLoop) {
        withContext(ioDispatcher) { practiceDao.upsert(loop.toEntity()) }
    }

    override suspend fun deleteLoop(loopId: String) {
        withContext(ioDispatcher) { practiceDao.delete(loopId) }
    }
}
