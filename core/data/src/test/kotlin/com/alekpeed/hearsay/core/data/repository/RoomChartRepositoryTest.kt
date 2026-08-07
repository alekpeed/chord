package com.alekpeed.hearsay.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alekpeed.hearsay.core.common.time.TimeProvider
import com.alekpeed.hearsay.core.data.mapper.toEntity
import com.alekpeed.hearsay.core.database.HearsayDatabase
import com.alekpeed.hearsay.core.model.chart.ManualChart
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.music.SymbolStyle
import com.alekpeed.hearsay.core.model.project.AnalysisStatus
import com.alekpeed.hearsay.core.model.project.Project
import com.alekpeed.hearsay.core.model.project.RevisionSource
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The behaviour these tests exist for: a correction must never destroy what the analysis said.
 */
@RunWith(RobolectricTestRunner::class)
class RoomChartRepositoryTest {

    private lateinit var database: HearsayDatabase
    private lateinit var chartRepository: RoomChartRepository
    private lateinit var projectRepository: RoomProjectRepository

    private var now = 1_000L
    private val timeProvider = TimeProvider { now++ }

    @Before
    fun setUp() {
        // The repositories only hop threads to keep Room off the caller's thread; in a test the
        // hop is noise, and its own scheduler would fight runTest's.
        val dispatcher = Dispatchers.Unconfined
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HearsayDatabase::class.java,
        ).build()

        chartRepository = RoomChartRepository(
            chartDao = database.chartDao(),
            revisionDao = database.revisionDao(),
            projectDao = database.projectDao(),
            timeProvider = timeProvider,
            ioDispatcher = dispatcher,
        )
        projectRepository = RoomProjectRepository(
            projectDao = database.projectDao(),
            timeProvider = timeProvider,
            ioDispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seedProject(chart: SongChart = twoBarChart()): String {
        val id = "project-1"
        database.projectDao().upsertProject(
            Project(
                id = id,
                title = "Take the A Train",
                artist = "Duke Ellington",
                album = null,
                createdAtMs = 0,
                updatedAtMs = 0,
                durationMs = 8_000,
                analysisStatus = AnalysisStatus.COMPLETE,
                activeRevisionId = null,
                keyLabel = "C major",
            ).toEntity(),
        )
        chartRepository.replaceChart(id, chart, "Analysis", revisionSourceIsUser = false)
        return id
    }

    private fun twoBarChart(): SongChart {
        val grid = ManualChart.blankGrid(durationMs = 8_000, bpm = 120f)
        val symbols = listOf("Cmaj7", "Dm7", "G7", "Cmaj7")
        return SongChart.of(
            chordEvents = grid.chordEvents.mapIndexed { index, event ->
                event.copy(chord = ChordParser.parse(symbols[index % symbols.size]), confidence = 0.6f)
            },
            beats = grid.beats,
            tempoSegments = grid.tempoSegments,
        )
    }

    @Test
    fun `a stored chart comes back through the flow`() = runTest {
        val projectId = seedProject()
        val chart = chartRepository.observeChart(projectId).first()

        assertEquals(4, chart.chordEvents.size)
        assertEquals("Cmaj7", chart.chordEvents.first().chord?.let {
            ChordFormatter.format(it, SymbolStyle.Ascii)
        })
        assertEquals(16, chart.beats.size)
    }

    @Test
    fun `the project key is parsed back out of its stored label`() = runTest {
        val projectId = seedProject()
        assertEquals("C", chartRepository.observeChart(projectId).first().key?.tonic?.toString())
    }

    @Test
    fun `the first correction forks a user revision and leaves the machine result intact`() = runTest {
        val projectId = seedProject()
        val before = chartRepository.observeChart(projectId).first()
        val machineRevisions = chartRepository.revisions(projectId)
        assertEquals(1, machineRevisions.size)
        assertEquals(RevisionSource.MACHINE, machineRevisions.single().source)

        val target = before.chordEvents[1]
        val newRevisionId = chartRepository.updateChord(projectId, target.id, ChordParser.parse("Dm7b5"))

        val revisions = chartRepository.revisions(projectId)
        assertEquals(2, revisions.size)
        assertNotEquals(machineRevisions.single().id, newRevisionId)
        assertTrue(revisions.any { it.source == RevisionSource.MACHINE })
        assertTrue(revisions.any { it.source == RevisionSource.USER })

        // The machine's rows are still on disk, untouched.
        val machineRows = database.chartDao().chords(machineRevisions.single().id)
        assertEquals("Dm7", machineRows[1].displaySymbol)
    }

    @Test
    fun `a second correction reuses the same user revision`() = runTest {
        val projectId = seedProject()
        val chart = chartRepository.observeChart(projectId).first()

        val first = chartRepository.updateChord(projectId, chart.chordEvents[1].id, ChordParser.parse("Dm9"))
        val updated = chartRepository.observeChart(projectId).first()
        val second = chartRepository.updateChord(projectId, updated.chordEvents[2].id, ChordParser.parse("G7alt"))

        assertEquals(first, second)
        assertEquals(2, chartRepository.revisions(projectId).size)
    }

    @Test
    fun `a correction is marked as the user's work`() = runTest {
        val projectId = seedProject()
        val chart = chartRepository.observeChart(projectId).first()
        chartRepository.updateChord(projectId, chart.chordEvents[0].id, ChordParser.parse("C6/9"))

        val corrected = chartRepository.observeChart(projectId).first().chordEvents[0]
        assertTrue(corrected.userConfirmed)
        assertEquals("C6/9", corrected.chord?.let {
            ChordFormatter.format(it, SymbolStyle.Ascii)
        })
    }

    @Test
    fun `a chord can be cleared to no chord`() = runTest {
        val projectId = seedProject()
        val chart = chartRepository.observeChart(projectId).first()
        chartRepository.updateChord(projectId, chart.chordEvents[0].id, null)

        assertNull(chartRepository.observeChart(projectId).first().chordEvents[0].chord)
    }

    @Test
    fun `restoring the machine result switches back and the correction is still there to return to`() = runTest {
        val projectId = seedProject()
        val chart = chartRepository.observeChart(projectId).first()
        val userRevisionId = chartRepository.updateChord(projectId, chart.chordEvents[1].id, ChordParser.parse("Db7"))

        val restored = chartRepository.restoreMachineResult(projectId)
        assertNotEquals(userRevisionId, restored)
        assertEquals("Dm7", chartRepository.observeChart(projectId).first().chordEvents[1].displaySymbolAscii())

        chartRepository.setActiveRevision(projectId, userRevisionId)
        assertEquals("Db7", chartRepository.observeChart(projectId).first().chordEvents[1].displaySymbolAscii())
    }

    @Test
    fun `deleting a project removes its chart`() = runTest {
        val projectId = seedProject()
        projectRepository.deleteProject(projectId)

        assertTrue(chartRepository.observeChart(projectId).first().isEmpty)
        assertTrue(chartRepository.revisions(projectId).isEmpty())
    }

    private fun ChordEvent.displaySymbolAscii(): String =
        chord?.let { ChordFormatter.format(it, SymbolStyle.Ascii) } ?: "N.C."
}
