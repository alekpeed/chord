package com.alekpeed.hearsay.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.alekpeed.hearsay.core.database.entity.EarTrainingAttemptEntity
import com.alekpeed.hearsay.core.database.entity.EarTrainingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EarTrainingDao {

    @Query("SELECT * FROM ear_training_sessions ORDER BY createdAtMs DESC LIMIT :limit")
    fun observeSessions(limit: Int = 30): Flow<List<EarTrainingSessionEntity>>

    @Query("SELECT * FROM ear_training_attempts WHERE sessionId = :sessionId")
    suspend fun attempts(sessionId: String): List<EarTrainingAttemptEntity>

    /** Accuracy per exercise type across everything answered, which drives the weak-topic view. */
    @Query(
        """
        SELECT exerciseType AS type,
               COUNT(*) AS total,
               SUM(CASE WHEN correct THEN 1 ELSE 0 END) AS correct
        FROM ear_training_attempts
        GROUP BY exerciseType
        """,
    )
    fun observeSkillTotals(): Flow<List<SkillTotal>>

    @Upsert
    suspend fun upsertSession(session: EarTrainingSessionEntity)

    @Upsert
    suspend fun upsertAttempt(attempt: EarTrainingAttemptEntity)

    @Query("DELETE FROM ear_training_sessions")
    suspend fun clearHistory()
}

data class SkillTotal(val type: String, val total: Int, val correct: Int) {
    val accuracy: Float get() = if (total == 0) 0f else correct.toFloat() / total
}
