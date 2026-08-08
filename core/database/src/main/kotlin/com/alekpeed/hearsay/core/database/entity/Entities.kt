package com.alekpeed.hearsay.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "projects",
    indices = [Index("updatedAtMs"), Index("title")],
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val tags: List<String>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val durationMs: Long,
    val analysisStatus: String,
    val analysisProfile: String,
    val activeRevisionId: String?,
    val keyLabel: String?,
    val tempoBpm: Float?,
    val lastPracticedAtMs: Long?,
)

/**
 * A file the project uses. Large media never lives in the database — only where to find it,
 * how big it is, and whether it can still be opened.
 */
@Entity(
    tableName = "media_assets",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("checksum")],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val role: String,
    val uri: String,
    val storageMode: String,
    val mimeType: String?,
    val durationMs: Long,
    val sampleRate: Int?,
    val channels: Int?,
    val fileSizeBytes: Long?,
    val checksum: String?,
    val availability: String,
    val displayName: String?,
)

@Entity(
    tableName = "revisions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class RevisionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val parentRevisionId: String?,
    val createdAtMs: Long,
    val label: String,
    val source: String,
    val description: String?,
)

/**
 * A chord region.
 *
 * [chordJson] is the structured chord and the only source of truth. [displaySymbol] and the
 * pitch-class columns are denormalized copies so the library can search by chord without
 * deserializing every row.
 */
@Entity(
    tableName = "chord_events",
    foreignKeys = [
        ForeignKey(
            entity = RevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId", "startMs"), Index("rootPitchClass")],
)
data class ChordEventEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    /** Stable across revisions, so a corrected chord can be traced back to what the model said. */
    val localId: String,
    val startMs: Long,
    val endMs: Long,
    val chordJson: String?,
    val displaySymbol: String,
    val rootPitchClass: Int?,
    val bassPitchClass: Int?,
    val confidence: Float,
    val source: String,
    val userConfirmed: Boolean,
)

@Entity(
    tableName = "beat_events",
    foreignKeys = [
        ForeignKey(
            entity = RevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId", "timeMs")],
)
data class BeatEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val revisionId: String,
    val timeMs: Long,
    val beatInMeasure: Int,
    val measureNumber: Int,
    val confidence: Float,
    val source: String,
)

@Entity(
    tableName = "tempo_segments",
    foreignKeys = [
        ForeignKey(
            entity = RevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId", "startMs")],
)
data class TempoSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val revisionId: String,
    val startMs: Long,
    val endMs: Long,
    val bpm: Float,
    val confidence: Float,
)

@Entity(
    tableName = "sections",
    foreignKeys = [
        ForeignKey(
            entity = RevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId", "startMs")],
)
data class SectionEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    val localId: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val orderIndex: Int,
    val confidence: Float,
    val source: String,
)

@Entity(
    tableName = "saved_loops",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class SavedLoopEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val label: String?,
    val startMs: Long,
    val endMs: Long,
    val speed: Float,
    @ColumnInfo(defaultValue = "0") val transposeSemitones: Int,
)

/** A project together with its files, which is what every library row needs. */
data class ProjectWithAssets(
    @Embedded val project: ProjectEntity,
    @Relation(parentColumn = "id", entityColumn = "projectId")
    val assets: List<MediaAssetEntity>,
)

/**
 * One run of the analysis pipeline over one project.
 *
 * Jobs are rows rather than in-memory state so that a job survives process death: the app can be
 * killed mid-analysis and still know, on restart, exactly which stages had finished.
 */
@Entity(
    tableName = "analysis_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("status")],
)
data class AnalysisJobEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val backend: String,
    val profile: String,
    val status: String,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val progress: Float,
    val failureCode: String?,
    val failureMessage: String?,
)

/**
 * A checkpoint within a job.
 *
 * [inputFingerprint] is what makes reprocessing selective: a stage whose inputs and settings are
 * unchanged does not run again, and a change upstream invalidates only what depended on it.
 */
@Entity(
    tableName = "analysis_stages",
    foreignKeys = [
        ForeignKey(
            entity = AnalysisJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("jobId")],
)
data class AnalysisStageEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val stageType: String,
    val status: String,
    val orderIndex: Int,
    val inputFingerprint: String?,
    val outputVersion: Int,
    val progress: Float,
    val modelId: String?,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val message: String?,
)

data class JobWithStages(
    @Embedded val job: AnalysisJobEntity,
    @Relation(parentColumn = "id", entityColumn = "jobId")
    val stages: List<AnalysisStageEntity>,
)

@Entity(tableName = "ear_training_sessions")
data class EarTrainingSessionEntity(
    @PrimaryKey val id: String,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val mode: String,
    val projectScope: String?,
    val score: Float,
    val total: Int,
)

/**
 * One answered question.
 *
 * [sourceEventId] and [confidenceAtGeneration] are kept so a disputed result can be traced to the
 * chord it came from and to how sure the app was at the time.
 */
@Entity(
    tableName = "ear_training_attempts",
    foreignKeys = [
        ForeignKey(
            entity = EarTrainingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("exerciseType")],
)
data class EarTrainingAttemptEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val projectId: String,
    val sourceEventId: String,
    val exerciseType: String,
    val prompt: String,
    val correctAnswer: String,
    val response: String?,
    val correct: Boolean,
    val confidenceAtGeneration: Float,
    val replayCount: Int,
    val isolatedStemUsed: Boolean,
    val responseTimeMs: Long,
)

/**
 * A chord the analysis also considered.
 *
 * Alternates are the product's core claim made concrete: the app shows what else it heard rather
 * than presenting one answer as fact. They are stored per chord event and die with it.
 */
@Entity(
    tableName = "chord_alternatives",
    foreignKeys = [
        ForeignKey(
            entity = ChordEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["chordEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chordEventId")],
)
data class ChordAlternativeEntity(
    @PrimaryKey val id: String,
    val chordEventId: String,
    val rank: Int,
    val chordJson: String,
    val displaySymbol: String,
    val confidence: Float,
)
