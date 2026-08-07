package com.alekpeed.hearsay.core.model.project

/** Whether the app holds its own copy of the audio or points at the user's file where it lives. */
enum class StorageMode { REFERENCED, MANAGED_COPY }

/** Whether the source audio can actually be opened right now. */
enum class SourceAvailability { AVAILABLE, MISSING, PERMISSION_LOST, UNKNOWN }

enum class AnalysisStatus { NOT_STARTED, QUEUED, RUNNING, PARTIAL, COMPLETE, FAILED }

/** The processing effort a project was imported with. Kept here so it survives re-analysis. */
enum class AnalysisProfile { FAST, BALANCED, MAXIMUM_QUALITY }

enum class MediaRole { SOURCE, PROXY, STEM, WAVEFORM, EXPORT, TEMPORARY }

data class MediaAsset(
    val id: String,
    val projectId: String,
    val role: MediaRole,
    val uri: String,
    val storageMode: StorageMode,
    val mimeType: String?,
    val durationMs: Long,
    val sampleRate: Int?,
    val channels: Int?,
    val fileSizeBytes: Long?,
    val checksum: String?,
    val availability: SourceAvailability,
    val displayName: String?,
)

data class Project(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val tags: List<String> = emptyList(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val durationMs: Long,
    val analysisStatus: AnalysisStatus = AnalysisStatus.NOT_STARTED,
    val analysisProfile: AnalysisProfile = AnalysisProfile.BALANCED,
    val activeRevisionId: String?,
    val keyLabel: String? = null,
    val tempoBpm: Float? = null,
    val lastPracticedAtMs: Long? = null,
)

/** A project plus the source it plays from, which is what the library and player screens need. */
data class ProjectWithSource(
    val project: Project,
    val source: MediaAsset?,
) {
    val isPlayable: Boolean
        get() = source != null && source.availability == SourceAvailability.AVAILABLE
}

/**
 * A named point in a project's edit history. Machine analysis writes the base revision; every user
 * correction lands on a later one, so the original result is always still there to restore.
 */
data class Revision(
    val id: String,
    val projectId: String,
    val parentRevisionId: String?,
    val createdAtMs: Long,
    val label: String,
    val source: RevisionSource,
    val description: String? = null,
)

enum class RevisionSource { MACHINE, USER, SEED, IMPORT }
