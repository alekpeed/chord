package com.alekpeed.hearsay.core.model.repository

import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.MediaAsset
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.Revision
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.coroutines.flow.Flow

/**
 * Domain-level access to the local library. Feature modules depend on this, never on Room.
 */
interface ProjectRepository {
    fun observeLibrary(): Flow<List<ProjectWithSource>>
    fun observeProject(projectId: String): Flow<ProjectWithSource?>

    suspend fun getProject(projectId: String): ProjectWithSource?
    suspend fun updateMetadata(projectId: String, title: String, artist: String?, album: String?)
    suspend fun updateAvailability(mediaAssetId: String, availability: SourceAvailability)
    suspend fun relinkSource(projectId: String, uri: String, storageMode: StorageMode)
    suspend fun markPracticed(projectId: String, atMs: Long)

    /** Deletes the project and everything derived from it. Referenced source files are never touched. */
    suspend fun deleteProject(projectId: String)
}

/** The result of pulling a file in through the system picker. */
sealed interface ImportResult {
    data class Success(val projectId: String) : ImportResult
    data class AlreadyImported(val projectId: String, val title: String) : ImportResult
    data class Failed(val reason: ImportFailure) : ImportResult
}

sealed interface ImportFailure {
    data object PermissionDenied : ImportFailure
    data object UnsupportedMedia : ImportFailure
    data object InsufficientStorage : ImportFailure
    data class Unreadable(val message: String) : ImportFailure
}

interface MediaImportRepository {
    suspend fun import(
        uri: String,
        storageMode: StorageMode,
        profile: AnalysisProfile,
    ): ImportResult

    suspend fun probe(uri: String): MediaAsset?
}

/**
 * Chart data for the active revision of a project.
 *
 * Edits are additive: [updateChord] records a correction against a user revision and leaves the
 * machine result reachable through [revisions].
 */
interface ChartRepository {
    fun observeChart(projectId: String): Flow<SongChart>

    suspend fun revisions(projectId: String): List<Revision>
    suspend fun replaceChart(projectId: String, chart: SongChart, label: String, revisionSourceIsUser: Boolean): String
    suspend fun updateChord(projectId: String, eventId: String, chord: Chord?): String
    suspend fun confirmChord(projectId: String, eventId: String, confirmed: Boolean)
    suspend fun restoreMachineResult(projectId: String): String?
    suspend fun setActiveRevision(projectId: String, revisionId: String)
}

/** Practice state that outlives a session: saved loops, speed and transposition per project. */
interface PracticeRepository {
    fun observeLoops(projectId: String): Flow<List<SavedLoop>>
    suspend fun saveLoop(loop: SavedLoop)
    suspend fun deleteLoop(loopId: String)
}

data class SavedLoop(
    val id: String,
    val projectId: String,
    val label: String?,
    val startMs: Long,
    val endMs: Long,
    val speed: Float,
    val transposeSemitones: Int,
)
