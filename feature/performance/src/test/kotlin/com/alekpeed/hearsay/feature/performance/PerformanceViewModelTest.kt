package com.alekpeed.hearsay.feature.performance

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.music.Key
import com.alekpeed.hearsay.core.model.music.Letter
import com.alekpeed.hearsay.core.model.music.NoteSpelling
import com.alekpeed.hearsay.core.model.music.SymbolStyle
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChartDisplayOptions
import com.alekpeed.hearsay.core.model.timeline.ChartRow
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.ChordNotation
import com.alekpeed.hearsay.core.model.timeline.SongChart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModelTest {

    private val playback = FakePlaybackController()
    private lateinit var projects: FakeProjectRepository
    private lateinit var charts: FakeChartRepository
    private lateinit var analyses: FakeAnalysisRepository
    private val launcher = FakeAnalysisLauncher()

    private fun chart(): SongChart {
        val symbols = listOf("Cmaj7", "Am7", "Dm7", "G7")
        return SongChart.of(
            chordEvents = symbols.mapIndexed { index, symbol ->
                ChordEvent(
                    id = "r1:e$index",
                    startMs = index * 4000L,
                    endMs = (index + 1) * 4000L,
                    chord = ChordParser.parse(symbol),
                    confidence = 0.65f,
                )
            },
            beats = (0 until 16).map { index ->
                BeatEvent(
                    timeMs = index * 1000L,
                    beatInMeasure = index % 4 + 1,
                    measureNumber = index / 4 + 1,
                )
            },
            key = Key(NoteSpelling(Letter.C)),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        projects = FakeProjectRepository()
        charts = FakeChartRepository(chart())
        analyses = FakeAnalysisRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle(mapOf("projectId" to "p1"))) =
        PerformanceViewModel(savedState, projects, charts, playback, analyses, launcher) to savedState

    @Test
    fun `builds a row for every chord region`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test {
            val ready = awaitReadyState()
            assertEquals(4, ready.rows.size)
            assertEquals(listOf(1, 2, 3, 4), ready.rows.map { it.measureNumber })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the current row follows the playback position`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test {
            assertEquals(0, awaitReadyState().currentRowIndex)

            playback.advanceTo(5_000)
            assertEquals(1, awaitItemWhere { it.currentRowIndex == 1 }.currentRowIndex)

            playback.advanceTo(12_000)
            assertEquals(3, awaitItemWhere { it.currentRowIndex == 3 }.currentRowIndex)

            // Past the end of the last region there is no current row at all.
            playback.advanceTo(20_000)
            assertEquals(-1, awaitItemWhere { it.currentRowIndex == -1 }.currentRowIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `previous and next measure seek to bar lines`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test { awaitReadyState() }

        // Bars are four seconds long here: downbeats fall at 0, 4000, 8000 and 12000.
        playback.advanceTo(5_500)
        model.actions.onNextMeasure()
        assertEquals(8_000L, playback.seeks.last())

        // Now sitting exactly on the downbeat at 8000, "previous" means the bar before it.
        model.actions.onPreviousMeasure()
        assertEquals(4_000L, playback.seeks.last())
    }

    @Test
    fun `transposing changes the display but stores what the user typed at concert pitch`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test {
            awaitReadyState()

            model.actions.onTransposeChange(2)
            // Display options and rebuilt rows arrive as separate emissions; wait for the rows.
            val transposed = awaitItemWhere { it.rows.firstOrNull()?.displaySymbolAscii() == "Dmaj7" }
            assertEquals(2, transposed.display.transposeSemitones)

            // The user sees D and types D. What must be stored is the concert-pitch C.
            model.actions.onChordEdited(0, "Dmaj9")
            cancelAndIgnoreRemainingEvents()
        }

        val (eventId, stored) = charts.updates.single()
        assertEquals("r1:e0", eventId)
        assertEquals("Cmaj9", ChordFormatter.format(requireNotNull(stored), SymbolStyle.Ascii))
    }

    @Test
    fun `an unparseable correction is not written`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test { awaitReadyState() }

        model.actions.onChordEdited(0, "not a chord")

        assertTrue(charts.updates.isEmpty())
    }

    @Test
    fun `clearing a chord stores no chord rather than failing`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test { awaitReadyState() }

        model.actions.onChordEdited(1, "N.C.")

        assertEquals("r1:e1", charts.updates.single().first)
        assertNull(charts.updates.single().second)
    }

    @Test
    fun `looping the selected row sets the range and rewinds to it`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test {
            awaitReadyState()
            model.actions.onRowSelected(2)
            awaitItemWhere { it.selectedRowIndex == 2 }
            model.actions.onLoopSelectedRow()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(8_000L, playback.state.value.loop?.startMs)
        assertEquals(12_000L, playback.state.value.loop?.endMs)
        assertEquals(8_000L, playback.seeks.last())
    }

    @Test
    fun `display settings survive the process being recreated`() = runTest {
        val (model, savedState) = viewModel()
        model.uiState.test {
            awaitReadyState()
            model.actions.onTransposeChange(-3)
            model.actions.onNotationChange(ChartDisplayOptions(notation = ChordNotation.NASHVILLE))
            model.actions.onAutoScrollChange(false)
            model.actions.onRowSelected(2)
            awaitItemWhere { it.selectedRowIndex == 2 }
            cancelAndIgnoreRemainingEvents()
        }

        // A new ViewModel over the same saved state is what the system hands back after process death.
        val (restored, _) = viewModel(savedState)
        restored.uiState.test {
            val ready = awaitReadyState()
            assertEquals(-3, ready.display.transposeSemitones)
            assertEquals(ChordNotation.NASHVILLE, ready.display.notation)
            assertEquals(false, ready.autoScroll)
            assertEquals(2, ready.selectedRowIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playback is prepared from the project source`() = runTest {
        val (model, _) = viewModel()
        model.preparePlayback()

        assertEquals("content://media/1", playback.preparedRequests.single().uri)
        assertEquals("Autumn Leaves", playback.preparedRequests.single().title)
    }

    @Test
    fun `asking for analysis launches it for this project`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test { awaitReadyState() }

        model.actions.onAnalyze(com.alekpeed.hearsay.core.model.project.AnalysisProfile.MAXIMUM_QUALITY)

        assertEquals(
            "p1" to com.alekpeed.hearsay.core.model.project.AnalysisProfile.MAXIMUM_QUALITY,
            launcher.started.single(),
        )
    }

    @Test
    fun `cancelling analysis reaches the launcher`() = runTest {
        val (model, _) = viewModel()
        model.uiState.test { awaitReadyState() }

        model.actions.onCancelAnalysis()

        assertEquals("p1", launcher.cancelled.single())
    }

    @Test
    fun `an empty chart reports that there is nothing to follow`() = runTest {
        charts = FakeChartRepository(SongChart.Empty)
        val (model, _) = viewModel()
        model.uiState.test {
            val ready = awaitReadyState()
            assertTrue(ready.rows.isEmpty())
            assertEquals(false, ready.hasChart)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun ReceiveTurbine<PerformanceUiState>.awaitReadyState(): PerformanceUiState.Ready =
        awaitItemWhere { true }

    private suspend fun ReceiveTurbine<PerformanceUiState>.awaitItemWhere(
        predicate: (PerformanceUiState.Ready) -> Boolean,
    ): PerformanceUiState.Ready {
        repeat(MaxEmissions) {
            val item = awaitItem()
            if (item is PerformanceUiState.Ready && predicate(item)) return item
        }
        error("No matching Ready state within $MaxEmissions emissions")
    }

    private fun ChartRow.displaySymbolAscii(): String =
        chord?.let { ChordFormatter.format(it, SymbolStyle.Ascii) } ?: "N.C."

    private companion object {
        const val MaxEmissions = 20
    }
}
