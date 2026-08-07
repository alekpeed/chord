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
