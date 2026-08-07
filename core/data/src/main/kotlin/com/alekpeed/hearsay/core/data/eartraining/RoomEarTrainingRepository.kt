package com.alekpeed.hearsay.core.data.eartraining

import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.database.dao.EarTrainingDao
import com.alekpeed.hearsay.core.database.entity.EarTrainingAttemptEntity
import com.alekpeed.hearsay.core.database.entity.EarTrainingSessionEntity
import com.alekpeed.hearsay.core.model.eartraining.EarTrainingRepository
import com.alekpeed.hearsay.core.model.eartraining.EarTrainingSessionRecord
import com.alekpeed.hearsay.core.model.eartraining.ExerciseAttempt
import com.alekpeed.hearsay.core.model.eartraining.ExerciseType
import com.alekpeed.hearsay.core.model.eartraining.ListeningMode
import com.alekpeed.hearsay.core.model.eartraining.SessionSummary
import com.alekpeed.hearsay.core.model.eartraining.SkillAccuracy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomEarTrainingRepository @Inject constructor(
    private val dao: EarTrainingDao,
    private val timeProvider: TimeProvider,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : EarTrainingRepository {

    override fun observeSessions(): Flow<List<EarTrainingSessionRecord>> =
        dao.observeSessions().map { sessions ->
            sessions.map {
                EarTrainingSessionRecord(
                    id = it.id,
                    createdAtMs = it.createdAtMs,
                    completedAtMs = it.completedAtMs,
                    projectScope = it.projectScope,
                    score = it.score,
                    total = it.total,
                )
            }
        }.flowOn(ioDispatcher)

    override fun observeSkillAccuracy(): Flow<List<SkillAccuracy>> =
        dao.observeSkillTotals().map { totals ->
            totals.mapNotNull { total ->
                val type = runCatching { ExerciseType.valueOf(total.type) }.getOrNull() ?: return@mapNotNull null
                SkillAccuracy(type, total.total, total.correct)
            }
        }.flowOn(ioDispatcher)

    override suspend fun startSession(projectScope: String?): String = withContext(ioDispatcher) {
        val id = UUID.randomUUID().toString()
        startedAt[id] = timeProvider.nowMs()
        dao.upsertSession(
            EarTrainingSessionEntity(
                id = id,
                createdAtMs = timeProvider.nowMs(),
                completedAtMs = null,
                mode = "MIXED",
                projectScope = projectScope,
                score = 0f,
                total = 0,
            ),
        )
        id
    }

    /**
     * Attempts are written as they happen rather than at the end, so a session abandoned halfway
     * still counts toward what the user has practised.
     */
    override suspend fun recordAttempt(sessionId: String, attempt: ExerciseAttempt) {
        withContext(ioDispatcher) {
            dao.upsertAttempt(
                EarTrainingAttemptEntity(
                    id = "$sessionId:${attempt.exercise.id}",
                    sessionId = sessionId,
                    projectId = attempt.exercise.projectId,
                    sourceEventId = attempt.exercise.sourceEventId,
                    exerciseType = attempt.exercise.type.name,
                    prompt = attempt.exercise.prompt,
                    correctAnswer = attempt.exercise.correctAnswer,
                    response = attempt.answer,
                    correct = attempt.correct,
                    confidenceAtGeneration = attempt.exercise.confidenceAtGeneration,
                    replayCount = attempt.replayCount,
                    isolatedStemUsed = attempt.listeningMode == ListeningMode.ISOLATED,
                    responseTimeMs = attempt.responseTimeMs,
                ),
            )
        }
    }

    override suspend fun completeSession(sessionId: String, summary: SessionSummary) {
        withContext(ioDispatcher) {
            dao.upsertSession(
                EarTrainingSessionEntity(
                    id = sessionId,
                    // The original creation time is preserved so history reads in the right order.
                    createdAtMs = startedAt.remove(sessionId) ?: timeProvider.nowMs(),
                    completedAtMs = timeProvider.nowMs(),
                    mode = "MIXED",
                    projectScope = null,
                    score = summary.accuracy,
                    total = summary.total,
                ),
            )
        }
    }

    override suspend fun clearHistory() {
        withContext(ioDispatcher) { dao.clearHistory() }
    }

    private val startedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
}
