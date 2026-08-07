package com.alekpeed.hearsay.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alekpeed.hearsay.core.database.dao.AnalysisDao
import com.alekpeed.hearsay.core.database.dao.ChartDao
import com.alekpeed.hearsay.core.database.dao.EarTrainingDao
import com.alekpeed.hearsay.core.database.dao.PracticeDao
import com.alekpeed.hearsay.core.database.dao.ProjectDao
import com.alekpeed.hearsay.core.database.dao.RevisionDao
import com.alekpeed.hearsay.core.database.entity.AnalysisJobEntity
import com.alekpeed.hearsay.core.database.entity.AnalysisStageEntity
import com.alekpeed.hearsay.core.database.entity.BeatEventEntity
import com.alekpeed.hearsay.core.database.entity.ChordEventEntity
import com.alekpeed.hearsay.core.database.entity.EarTrainingAttemptEntity
import com.alekpeed.hearsay.core.database.entity.EarTrainingSessionEntity
import com.alekpeed.hearsay.core.database.entity.MediaAssetEntity
import com.alekpeed.hearsay.core.database.entity.ProjectEntity
import com.alekpeed.hearsay.core.database.entity.RevisionEntity
import com.alekpeed.hearsay.core.database.entity.SavedLoopEntity
import com.alekpeed.hearsay.core.database.entity.SectionEntity
import com.alekpeed.hearsay.core.database.entity.TempoSegmentEntity
import kotlinx.serialization.json.Json

/** Stores a list of strings as JSON. Tags are short and few; a join table would cost more than it pays. */
class StringListConverter {
    @TypeConverter
    fun fromJson(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else Json.decodeFromString(value)

    @TypeConverter
    fun toJson(value: List<String>): String = Json.encodeToString(value)
}

@Database(
    entities = [
        ProjectEntity::class,
        MediaAssetEntity::class,
        RevisionEntity::class,
        ChordEventEntity::class,
        BeatEventEntity::class,
        TempoSegmentEntity::class,
        SectionEntity::class,
        SavedLoopEntity::class,
        AnalysisJobEntity::class,
        AnalysisStageEntity::class,
        EarTrainingSessionEntity::class,
        EarTrainingAttemptEntity::class,
    ],
    version = HearsayDatabase.Version,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class)
abstract class HearsayDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun chartDao(): ChartDao
    abstract fun revisionDao(): RevisionDao
    abstract fun practiceDao(): PracticeDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun earTrainingDao(): EarTrainingDao

    companion object {
        const val Version = 3
        const val Name = "hearsay.db"

        /**
         * Adds the analysis job and stage tables.
         *
         * Written by hand and tested rather than generated, because destructive migration is never
         * an option here: the rows below a user's project include every correction they have made.
         */
        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `analysis_jobs` (
                        `id` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `backend` TEXT NOT NULL,
                        `profile` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `startedAtMs` INTEGER,
                        `completedAtMs` INTEGER,
                        `progress` REAL NOT NULL,
                        `failureCode` TEXT,
                        `failureMessage` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_jobs_projectId` ON `analysis_jobs` (`projectId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_jobs_status` ON `analysis_jobs` (`status`)")
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `analysis_stages` (
                        `id` TEXT NOT NULL,
                        `jobId` TEXT NOT NULL,
                        `stageType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `orderIndex` INTEGER NOT NULL,
                        `inputFingerprint` TEXT,
                        `outputVersion` INTEGER NOT NULL,
                        `progress` REAL NOT NULL,
                        `modelId` TEXT,
                        `startedAtMs` INTEGER,
                        `completedAtMs` INTEGER,
                        `message` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`jobId`) REFERENCES `analysis_jobs`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_analysis_stages_jobId` ON `analysis_stages` (`jobId`)")
            }
        }

        /** Adds ear-training history. Sessions and attempts are additive; nothing existing moves. */
        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ear_training_sessions` (
                        `id` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `completedAtMs` INTEGER,
                        `mode` TEXT NOT NULL,
                        `projectScope` TEXT,
                        `score` REAL NOT NULL,
                        `total` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ear_training_attempts` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `sourceEventId` TEXT NOT NULL,
                        `exerciseType` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `correctAnswer` TEXT NOT NULL,
                        `response` TEXT,
                        `correct` INTEGER NOT NULL,
                        `confidenceAtGeneration` REAL NOT NULL,
                        `replayCount` INTEGER NOT NULL,
                        `isolatedStemUsed` INTEGER NOT NULL,
                        `responseTimeMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `ear_training_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ear_training_attempts_sessionId` " +
                        "ON `ear_training_attempts` (`sessionId`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ear_training_attempts_exerciseType` " +
                        "ON `ear_training_attempts` (`exerciseType`)",
                )
            }
        }
    }
}
