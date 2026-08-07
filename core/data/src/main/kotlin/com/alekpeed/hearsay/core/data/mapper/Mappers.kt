package com.alekpeed.hearsay.core.data.mapper

import com.alekpeed.hearsay.core.database.dao.ChartDao
import com.alekpeed.hearsay.core.database.entity.BeatEventEntity
import com.alekpeed.hearsay.core.database.entity.ChordEventEntity
import com.alekpeed.hearsay.core.database.entity.MediaAssetEntity
import com.alekpeed.hearsay.core.database.entity.ProjectEntity
import com.alekpeed.hearsay.core.database.entity.ProjectWithAssets
import com.alekpeed.hearsay.core.database.entity.RevisionEntity
import com.alekpeed.hearsay.core.database.entity.SavedLoopEntity
import com.alekpeed.hearsay.core.database.entity.SectionEntity
import com.alekpeed.hearsay.core.database.entity.TempoSegmentEntity
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.project.AnalysisProfile
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.MediaAsset
import com.alekpeed.hearsay.core.model.project.MediaRole
import com.alekpeed.hearsay.core.model.project.Project
import com.alekpeed.hearsay.core.model.project.ProjectWithSource
import com.alekpeed.hearsay.core.model.project.Revision
import com.alekpeed.hearsay.core.model.project.RevisionSource
import com.alekpeed.hearsay.core.model.project.SourceAvailability
import com.alekpeed.hearsay.core.model.project.StorageMode
import com.alekpeed.hearsay.core.model.repository.SavedLoop
import com.alekpeed.hearsay.core.model.timeline.AnalysisSource
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SectionEvent
import com.alekpeed.hearsay.core.model.timeline.TempoSegment

/**
 * Reads an enum stored by name. Names rather than ordinals are used throughout so that reordering a
 * Kotlin enum can never silently reinterpret a user's saved data, and an unrecognized value falls
 * back instead of crashing a library that a newer version of the app wrote.
 */
private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
    runCatching { enumValueOf<T>(this) }.getOrDefault(fallback)

internal fun ProjectEntity.toDomain(): Project = Project(
    id = id,
    title = title,
    artist = artist,
    album = album,
    tags = tags,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    durationMs = durationMs,
    analysisStatus = analysisStatus.toEnumOr(AnalysisStatus.NOT_STARTED),
    analysisProfile = analysisProfile.toEnumOr(AnalysisProfile.BALANCED),
    activeRevisionId = activeRevisionId,
    keyLabel = keyLabel,
    tempoBpm = tempoBpm,
    lastPracticedAtMs = lastPracticedAtMs,
)

internal fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    tags = tags,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    durationMs = durationMs,
    analysisStatus = analysisStatus.name,
    analysisProfile = analysisProfile.name,
    activeRevisionId = activeRevisionId,
    keyLabel = keyLabel,
    tempoBpm = tempoBpm,
    lastPracticedAtMs = lastPracticedAtMs,
)

internal fun MediaAssetEntity.toDomain(): MediaAsset = MediaAsset(
    id = id,
    projectId = projectId,
    role = role.toEnumOr(MediaRole.SOURCE),
    uri = uri,
    storageMode = storageMode.toEnumOr(StorageMode.REFERENCED),
    mimeType = mimeType,
    durationMs = durationMs,
    sampleRate = sampleRate,
    channels = channels,
    fileSizeBytes = fileSizeBytes,
    checksum = checksum,
    availability = availability.toEnumOr(SourceAvailability.UNKNOWN),
    displayName = displayName,
)

internal fun MediaAsset.toEntity(): MediaAssetEntity = MediaAssetEntity(
    id = id,
    projectId = projectId,
    role = role.name,
    uri = uri,
    storageMode = storageMode.name,
    mimeType = mimeType,
    durationMs = durationMs,
    sampleRate = sampleRate,
    channels = channels,
    fileSizeBytes = fileSizeBytes,
    checksum = checksum,
    availability = availability.name,
    displayName = displayName,
)

internal fun ProjectWithAssets.toDomain(): ProjectWithSource = ProjectWithSource(
    project = project.toDomain(),
    source = assets.firstOrNull { it.role == MediaRole.SOURCE.name }?.toDomain(),
)

internal fun RevisionEntity.toDomain(): Revision = Revision(
    id = id,
    projectId = projectId,
    parentRevisionId = parentRevisionId,
    createdAtMs = createdAtMs,
    label = label,
    source = source.toEnumOr(RevisionSource.MACHINE),
    description = description,
)

internal fun ChordEventEntity.toDomain(): ChordEvent = ChordEvent(
    id = id,
    startMs = startMs,
    endMs = endMs,
    chord = ChartJson.decode(chordJson),
    confidence = confidence,
    source = source.toEnumOr(AnalysisSource.MACHINE),
    userConfirmed = userConfirmed,
)

internal fun ChordEvent.toEntity(revisionId: String, localId: String): ChordEventEntity {
    val chord = this.chord
    return ChordEventEntity(
        id = ChartDao.chordEventId(revisionId, localId),
        revisionId = revisionId,
        localId = localId,
        startMs = startMs,
        endMs = endMs,
        chordJson = ChartJson.encode(chord),
        displaySymbol = chord?.let { ChordFormatter.format(it) } ?: "N.C.",
        rootPitchClass = chord?.root?.pitchClass,
        bassPitchClass = chord?.effectiveBass?.pitchClass,
        confidence = confidence,
        source = source.name,
        userConfirmed = userConfirmed,
    )
}

internal fun BeatEventEntity.toDomain(): BeatEvent = BeatEvent(
    timeMs = timeMs,
    beatInMeasure = beatInMeasure,
    measureNumber = measureNumber,
    confidence = confidence,
    source = source.toEnumOr(AnalysisSource.MACHINE),
)

internal fun BeatEvent.toEntity(revisionId: String): BeatEventEntity = BeatEventEntity(
    revisionId = revisionId,
    timeMs = timeMs,
    beatInMeasure = beatInMeasure,
    measureNumber = measureNumber,
    confidence = confidence,
    source = source.name,
)

internal fun SectionEntity.toDomain(): SectionEvent = SectionEvent(
    id = id,
    label = label,
    startMs = startMs,
    endMs = endMs,
    orderIndex = orderIndex,
    confidence = confidence,
    source = source.toEnumOr(AnalysisSource.MACHINE),
)

internal fun SectionEvent.toEntity(revisionId: String, localId: String): SectionEntity = SectionEntity(
    id = ChartDao.sectionId(revisionId, localId),
    revisionId = revisionId,
    localId = localId,
    label = label,
    startMs = startMs,
    endMs = endMs,
    orderIndex = orderIndex,
    confidence = confidence,
    source = source.name,
)

internal fun TempoSegmentEntity.toDomain(): TempoSegment = TempoSegment(
    startMs = startMs,
    endMs = endMs,
    bpm = bpm,
    confidence = confidence,
)

internal fun TempoSegment.toEntity(revisionId: String): TempoSegmentEntity = TempoSegmentEntity(
    revisionId = revisionId,
    startMs = startMs,
    endMs = endMs,
    bpm = bpm,
    confidence = confidence,
)

internal fun SavedLoopEntity.toDomain(): SavedLoop = SavedLoop(
    id = id,
    projectId = projectId,
    label = label,
    startMs = startMs,
    endMs = endMs,
    speed = speed,
    transposeSemitones = transposeSemitones,
)

internal fun SavedLoop.toEntity(): SavedLoopEntity = SavedLoopEntity(
    id = id,
    projectId = projectId,
    label = label,
    startMs = startMs,
    endMs = endMs,
    speed = speed,
    transposeSemitones = transposeSemitones,
)
