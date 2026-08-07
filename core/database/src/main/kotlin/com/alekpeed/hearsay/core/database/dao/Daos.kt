package com.alekpeed.hearsay.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.alekpeed.hearsay.core.database.entity.BeatEventEntity
import com.alekpeed.hearsay.core.database.entity.ChordEventEntity
import com.alekpeed.hearsay.core.database.entity.MediaAssetEntity
import com.alekpeed.hearsay.core.database.entity.ProjectEntity
import com.alekpeed.hearsay.core.database.entity.ProjectWithAssets
import com.alekpeed.hearsay.core.database.entity.RevisionEntity
import com.alekpeed.hearsay.core.database.entity.SavedLoopEntity
import com.alekpeed.hearsay.core.database.entity.SectionEntity
import com.alekpeed.hearsay.core.database.entity.TempoSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Transaction
    @Query("SELECT * FROM projects ORDER BY updatedAtMs DESC")
    fun observeLibrary(): Flow<List<ProjectWithAssets>>

    @Transaction
    @Query("SELECT * FROM projects WHERE id = :projectId")
    fun observeProject(projectId: String): Flow<ProjectWithAssets?>

    @Transaction
    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProject(projectId: String): ProjectWithAssets?

    @Query("SELECT * FROM media_assets WHERE checksum = :checksum AND role = 'SOURCE' LIMIT 1")
    suspend fun findByChecksum(checksum: String): MediaAssetEntity?

    @Upsert
    suspend fun upsertProject(project: ProjectEntity)

    @Upsert
    suspend fun upsertAsset(asset: MediaAssetEntity)

    @Query("UPDATE media_assets SET availability = :availability WHERE id = :assetId")
    suspend fun updateAvailability(assetId: String, availability: String)

    @Query("UPDATE media_assets SET uri = :uri, storageMode = :storageMode, availability = 'AVAILABLE' WHERE id = :assetId")
    suspend fun relinkAsset(assetId: String, uri: String, storageMode: String)

    @Query(
        """
        UPDATE projects
        SET title = :title, artist = :artist, album = :album, updatedAtMs = :updatedAtMs
        WHERE id = :projectId
        """,
    )
    suspend fun updateMetadata(projectId: String, title: String, artist: String?, album: String?, updatedAtMs: Long)

    @Query("UPDATE projects SET lastPracticedAtMs = :atMs WHERE id = :projectId")
    suspend fun markPracticed(projectId: String, atMs: Long)

    @Query("UPDATE projects SET activeRevisionId = :revisionId, updatedAtMs = :updatedAtMs WHERE id = :projectId")
    suspend fun setActiveRevision(projectId: String, revisionId: String, updatedAtMs: Long)

    /** Removes the project row; every derived row follows it through cascading foreign keys. */
    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)
}

@Dao
interface RevisionDao {

    @Query("SELECT * FROM revisions WHERE projectId = :projectId ORDER BY createdAtMs ASC")
    suspend fun revisionsFor(projectId: String): List<RevisionEntity>

    @Query("SELECT * FROM revisions WHERE projectId = :projectId AND source = 'MACHINE' ORDER BY createdAtMs ASC LIMIT 1")
    suspend fun machineRevision(projectId: String): RevisionEntity?

    @Query("SELECT * FROM revisions WHERE id = :revisionId")
    suspend fun revision(revisionId: String): RevisionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(revision: RevisionEntity)
}

@Dao
interface ChartDao {

    @Query("SELECT * FROM chord_events WHERE revisionId = :revisionId ORDER BY startMs ASC")
    fun observeChords(revisionId: String): Flow<List<ChordEventEntity>>

    @Query("SELECT * FROM beat_events WHERE revisionId = :revisionId ORDER BY timeMs ASC")
    fun observeBeats(revisionId: String): Flow<List<BeatEventEntity>>

    @Query("SELECT * FROM sections WHERE revisionId = :revisionId ORDER BY startMs ASC")
    fun observeSections(revisionId: String): Flow<List<SectionEntity>>

    @Query("SELECT * FROM tempo_segments WHERE revisionId = :revisionId ORDER BY startMs ASC")
    fun observeTempo(revisionId: String): Flow<List<TempoSegmentEntity>>

    @Query("SELECT * FROM chord_events WHERE revisionId = :revisionId ORDER BY startMs ASC")
    suspend fun chords(revisionId: String): List<ChordEventEntity>

    @Query("SELECT * FROM beat_events WHERE revisionId = :revisionId ORDER BY timeMs ASC")
    suspend fun beats(revisionId: String): List<BeatEventEntity>

    @Query("SELECT * FROM sections WHERE revisionId = :revisionId ORDER BY startMs ASC")
    suspend fun sections(revisionId: String): List<SectionEntity>

    @Query("SELECT * FROM tempo_segments WHERE revisionId = :revisionId ORDER BY startMs ASC")
    suspend fun tempoSegments(revisionId: String): List<TempoSegmentEntity>

    @Upsert
    suspend fun upsertChords(chords: List<ChordEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeats(beats: List<BeatEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<SectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTempoSegments(segments: List<TempoSegmentEntity>)

    @Query("UPDATE chord_events SET userConfirmed = :confirmed WHERE id = :eventId")
    suspend fun setConfirmed(eventId: String, confirmed: Boolean)

    /**
     * Copies a whole revision's chart onto a new revision.
     *
     * This is how a correction stays non-destructive: the user edits a copy, and the revision the
     * machine wrote is still there to go back to.
     */
    @Transaction
    suspend fun copyChart(fromRevisionId: String, toRevisionId: String) {
        upsertChords(
            chords(fromRevisionId).map {
                it.copy(id = chordEventId(toRevisionId, it.localId), revisionId = toRevisionId)
            },
        )
        insertBeats(beats(fromRevisionId).map { it.copy(id = 0, revisionId = toRevisionId) })
        insertSections(
            sections(fromRevisionId).map {
                it.copy(id = sectionId(toRevisionId, it.localId), revisionId = toRevisionId)
            },
        )
        insertTempoSegments(tempoSegments(fromRevisionId).map { it.copy(id = 0, revisionId = toRevisionId) })
    }

    companion object {
        /** Row ids are derived so the same musical event keeps a predictable identity per revision. */
        fun chordEventId(revisionId: String, localId: String): String = "$revisionId:$localId"

        fun sectionId(revisionId: String, localId: String): String = "$revisionId:$localId"
    }
}

@Dao
interface PracticeDao {

    @Query("SELECT * FROM saved_loops WHERE projectId = :projectId ORDER BY startMs ASC")
    fun observeLoops(projectId: String): Flow<List<SavedLoopEntity>>

    @Upsert
    suspend fun upsert(loop: SavedLoopEntity)

    @Query("DELETE FROM saved_loops WHERE id = :loopId")
    suspend fun delete(loopId: String)
}
