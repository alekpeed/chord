package com.alekpeed.hearsay.core.data.repository

import com.alekpeed.hearsay.core.common.dispatchers.Dispatcher
import com.alekpeed.hearsay.core.common.dispatchers.HearsayDispatcher
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.data.mapper.ChartJson
import com.alekpeed.hearsay.core.data.mapper.toDomain
import com.alekpeed.hearsay.core.data.mapper.toEntity
import com.alekpeed.hearsay.core.database.dao.ChartDao
import com.alekpeed.hearsay.core.database.dao.ProjectDao
import com.alekpeed.hearsay.core.database.dao.RevisionDao
import com.alekpeed.hearsay.core.database.entity.ChordAlternativeEntity
import com.alekpeed.hearsay.core.database.entity.RevisionEntity
import com.alekpeed.hearsay.core.model.music.Chord
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.Key
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.project.Revision
import com.alekpeed.hearsay.core.model.project.RevisionSource
import com.alekpeed.hearsay.core.model.repository.ChartRepository
import com.alekpeed.hearsay.core.model.repository.ChordAlternative
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chart data for a project's active revision.
 *
 * The rule this class exists to enforce: a user correction never overwrites a machine result. The
 * first edit against a machine revision forks a user revision, and the original stays queryable
 * forever after.
 */
@Singleton
class RoomChartRepository @Inject constructor(
    private val chartDao: ChartDao,
    private val revisionDao: RevisionDao,
    private val projectDao: ProjectDao,
    private val timeProvider: TimeProvider,
    @Dispatcher(HearsayDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ChartRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChart(projectId: String): Flow<SongChart> =
        projectDao.observeProject(projectId)
            .map { it?.project?.activeRevisionId to it?.project?.keyLabel }
            .flatMapLatest { (revisionId, keyLabel) ->
                if (revisionId == null) {
                    flowOf(SongChart.Empty)
                } else {
                    combine(
                        chartDao.observeChords(revisionId),
                        chartDao.observeBeats(revisionId),
                        chartDao.observeSections(revisionId),
                        chartDao.observeTempo(revisionId),
                    ) { chords, beats, sections, tempo ->
                        SongChart.of(
                            chordEvents = chords.map { it.toDomain() },
                            beats = beats.map { it.toDomain() },
                            sections = sections.map { it.toDomain() },
                            tempoSegments = tempo.map { it.toDomain() },
                            key = keyLabel?.let(::parseKey),
                        )
                    }
                }
            }
            .flowOn(ioDispatcher)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAlternatives(projectId: String): Flow<Map<String, List<ChordAlternative>>> =
        projectDao.observeProject(projectId)
            .map { it?.project?.activeRevisionId }
            .flatMapLatest { revisionId ->
                if (revisionId == null) {
                    flowOf(emptyMap())
                } else {
                    chartDao.observeAlternatives(revisionId).map { rows ->
                        rows.groupBy { it.chordEventId }.mapValues { (_, group) ->
                            group.mapNotNull { row ->
                                val chord = ChartJson.decode(row.chordJson) ?: return@mapNotNull null
                                ChordAlternative(row.chordEventId, row.rank, chord, row.displaySymbol, row.confidence)
                            }
                        }
                    }
                }
            }
            .flowOn(ioDispatcher)

    override suspend fun replaceAlternatives(projectId: String, alternatives: List<ChordAlternative>) {
        withContext(ioDispatcher) {
            chartDao.upsertAlternatives(
                alternatives.map { alternative ->
                    ChordAlternativeEntity(
                        id = "${alternative.chordEventId}:alt" + alternative.rank,
                        chordEventId = alternative.chordEventId,
                        rank = alternative.rank,
                        chordJson = ChartJson.encode(alternative.chord).orEmpty(),
                        displaySymbol = alternative.displaySymbol,
                        confidence = alternative.confidence,
                    )
                },
            )
        }
    }

    /**
     * Splits a region at [atMs].
     *
     * Both halves keep the chord that was there — the split is a statement about *where* the
     * harmony changes, not yet about what it changes to, and the user names the second half next.
     */
    override suspend fun splitChordRegion(projectId: String, eventId: String, atMs: Long): String =
        withContext(ioDispatcher) {
            val revisionId = revisionForEditing(projectId)
            val localId = eventId.substringAfterLast(':')
            val target = chartDao.chords(revisionId).firstOrNull { it.localId == localId }
                ?: error("Chord event $eventId is not present in revision $revisionId")

            require(atMs > target.startMs && atMs < target.endMs) {
                "Split point $atMs is outside ${target.startMs}..${target.endMs}"
            }

            val secondLocalId = "${localId}s" + atMs
            chartDao.upsertChords(
                listOf(
                    target.copy(endMs = atMs, source = RevisionSource.USER.name),
                    target.copy(
                        id = ChartDao.chordEventId(revisionId, secondLocalId),
                        localId = secondLocalId,
                        startMs = atMs,
                        source = RevisionSource.USER.name,
                        userConfirmed = false,
                    ),
                ),
            )
            revisionId
        }

    override suspend fun mergeWithNext(projectId: String, eventId: String): String =
        withContext(ioDispatcher) {
            val revisionId = revisionForEditing(projectId)
            val localId = eventId.substringAfterLast(':')
            val chords = chartDao.chords(revisionId)
            val index = chords.indexOfFirst { it.localId == localId }
            require(index >= 0) { "Chord event $eventId is not present in revision $revisionId" }
            val next = chords.getOrNull(index + 1) ?: return@withContext revisionId

            chartDao.upsertChords(
                listOf(chords[index].copy(endMs = next.endMs, source = RevisionSource.USER.name)),
            )
            chartDao.deleteChord(next.id)
            revisionId
        }

    /**
     * Moves the boundary between a region and the one before it.
     *
     * Both regions are rewritten together: a boundary belongs to two chords, and moving one edge
     * without the other would leave a gap or an overlap in the timeline.
     */
    override suspend fun moveBoundary(projectId: String, eventId: String, newStartMs: Long): String =
        withContext(ioDispatcher) {
            val revisionId = revisionForEditing(projectId)
            val localId = eventId.substringAfterLast(':')
            val chords = chartDao.chords(revisionId)
            val index = chords.indexOfFirst { it.localId == localId }
            require(index >= 0) { "Chord event $eventId is not present in revision $revisionId" }

            val target = chords[index]
            val previous = chords.getOrNull(index - 1)
            val lowerBound = (previous?.startMs ?: 0L) + MinimumRegionMs
            val upperBound = target.endMs - MinimumRegionMs
            val clamped = newStartMs.coerceIn(lowerBound, upperBound)

            val updates = mutableListOf(target.copy(startMs = clamped, source = RevisionSource.USER.name))
            previous?.let { updates += it.copy(endMs = clamped, source = RevisionSource.USER.name) }
            chartDao.upsertChords(updates)
            revisionId
        }

    override suspend fun renameSection(projectId: String, sectionId: String, label: String): String =
        withContext(ioDispatcher) {
            val revisionId = revisionForEditing(projectId)
            val localId = sectionId.substringAfterLast(':')
            val section = chartDao.sections(revisionId).firstOrNull { it.localId == localId }
                ?: error("Section $sectionId is not present in revision $revisionId")
            chartDao.insertSections(listOf(section.copy(label = label, source = RevisionSource.USER.name)))
            revisionId
        }

    override suspend fun revisions(projectId: String): List<Revision> = withContext(ioDispatcher) {
        revisionDao.revisionsFor(projectId).map { it.toDomain() }
    }

    override suspend fun replaceChart(
        projectId: String,
        chart: SongChart,
        label: String,
        revisionSourceIsUser: Boolean,
    ): String = withContext(ioDispatcher) {
        val now = timeProvider.nowMs()
        val current = projectDao.getProject(projectId)?.project?.activeRevisionId
        val revisionId = UUID.randomUUID().toString()

        revisionDao.insert(
            RevisionEntity(
                id = revisionId,
                projectId = projectId,
                parentRevisionId = current,
                createdAtMs = now,
                label = label,
                source = if (revisionSourceIsUser) RevisionSource.USER.name else RevisionSource.MACHINE.name,
                description = null,
            ),
        )

        chartDao.upsertChords(
            chart.chordEvents.mapIndexed { index, event -> event.toEntity(revisionId, localId(index)) },
        )
        chartDao.insertBeats(chart.beats.map { it.toEntity(revisionId) })
        chartDao.insertSections(
            chart.sections.mapIndexed { index, section -> section.toEntity(revisionId, localId(index)) },
        )
        chartDao.insertTempoSegments(chart.tempoSegments.map { it.toEntity(revisionId) })

        projectDao.setActiveRevision(projectId, revisionId, now)
        revisionId
    }

    override suspend fun updateChord(projectId: String, eventId: String, chord: Chord?): String =
        withContext(ioDispatcher) {
            val revisionId = revisionForEditing(projectId)
            val localId = eventId.substringAfterLast(':')
            val target = chartDao.chords(revisionId).firstOrNull { it.localId == localId }
                ?: error("Chord event $eventId is not present in revision $revisionId")

            chartDao.upsertChords(
                listOf(
                    target.copy(
                        chordJson = ChartJson.encode(chord),
                        displaySymbol = chord?.let { ChordFormatter.format(it) } ?: "N.C.",
                        rootPitchClass = chord?.root?.pitchClass,
                        bassPitchClass = chord?.effectiveBass?.pitchClass,
                        source = RevisionSource.USER.name,
                        userConfirmed = true,
                    ),
                ),
            )
            revisionId
        }

    override suspend fun confirmChord(projectId: String, eventId: String, confirmed: Boolean) {
        withContext(ioDispatcher) {
            val revisionId = projectDao.getProject(projectId)?.project?.activeRevisionId ?: return@withContext
            val localId = eventId.substringAfterLast(':')
            chartDao.setConfirmed(ChartDao.chordEventId(revisionId, localId), confirmed)
        }
    }

    override suspend fun restoreMachineResult(projectId: String): String? = withContext(ioDispatcher) {
        val machine = revisionDao.machineRevision(projectId) ?: return@withContext null
        projectDao.setActiveRevision(projectId, machine.id, timeProvider.nowMs())
        machine.id
    }

    override suspend fun setActiveRevision(projectId: String, revisionId: String) {
        withContext(ioDispatcher) {
            projectDao.setActiveRevision(projectId, revisionId, timeProvider.nowMs())
        }
    }

    /**
     * Returns the revision an edit should be written to, forking a user revision from the machine
     * result the first time the user changes anything.
     */
    private suspend fun revisionForEditing(projectId: String): String {
        val project = projectDao.getProject(projectId)?.project
            ?: error("Project $projectId does not exist")
        val activeId = project.activeRevisionId ?: error("Project $projectId has no chart to edit")
        val active = revisionDao.revision(activeId) ?: error("Revision $activeId is missing")
        if (active.source == RevisionSource.USER.name) return activeId

        val now = timeProvider.nowMs()
        val forkedId = UUID.randomUUID().toString()
        revisionDao.insert(
            RevisionEntity(
                id = forkedId,
                projectId = projectId,
                parentRevisionId = activeId,
                createdAtMs = now,
                label = "Your corrections",
                source = RevisionSource.USER.name,
                description = "Forked from ${active.label}",
            ),
        )
        chartDao.copyChart(fromRevisionId = activeId, toRevisionId = forkedId)
        projectDao.setActiveRevision(projectId, forkedId, now)
        return forkedId
    }

    private fun parseKey(label: String): Key? {
        val parts = label.trim().split(' ')
        val tonic = NoteSpelling.parse(parts.firstOrNull().orEmpty()) ?: return null
        val mode = if (parts.getOrNull(1)?.startsWith("min", ignoreCase = true) == true) {
            com.alekpeed.hearsay.core.model.music.Mode.MINOR
        } else {
            com.alekpeed.hearsay.core.model.music.Mode.MAJOR
        }
        return Key(tonic, mode)
    }

    private fun localId(index: Int): String = "e%05d".format(index)

    private companion object {
        /** A region shorter than this cannot be read on a chart, so boundaries stop here. */
        const val MinimumRegionMs = 100L
    }
}
