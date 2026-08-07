package com.alekpeed.hearsay.feature.performance

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alekpeed.hearsay.core.model.music.ChordParser
import com.alekpeed.hearsay.core.model.timeline.BeatEvent
import com.alekpeed.hearsay.core.model.timeline.ChartRowBuilder
import com.alekpeed.hearsay.core.model.timeline.ChordEvent
import com.alekpeed.hearsay.core.model.timeline.SongChart
import com.alekpeed.hearsay.feature.performance.ui.ChordTable
import com.alekpeed.hearsay.feature.performance.ui.TableDensity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for the table.
 *
 * These need a device or emulator and are not run by CI yet — the CI runner has no KVM, so an
 * emulator would be unusably slow. Run them locally with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ChordTableUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rows = ChartRowBuilder.build(
        SongChart.of(
            chordEvents = listOf("Cmaj7", "Am7", "Dm7", "G7").mapIndexed { index, symbol ->
                ChordEvent(
                    id = "e$index",
                    startMs = index * 4000L,
                    endMs = (index + 1) * 4000L,
                    chord = ChordParser.parse(symbol),
                    confidence = 0.62f,
                )
            },
            beats = (0 until 16).map {
                BeatEvent(it * 1000L, it % 4 + 1, it / 4 + 1)
            },
        ),
    )

    @Test
    fun everyChordRegionGetsARow() {
        composeRule.setContent {
            ChordTable(
                rows = rows,
                currentRowIndex = 0,
                selectedRowIndex = null,
                chordsHidden = false,
                density = TableDensity.EXPANDED,
                listState = rememberLazyListState(),
                onRowSelected = {},
                onRowDoubleTapped = {},
            )
        }

        composeRule.onNode(hasContentDescription("Bar 1, Cmaj7, 62 percent confidence", substring = true))
            .assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Bar 4", substring = true)).assertIsDisplayed()
    }

    @Test
    fun tappingARowReportsItsIndex() {
        var selected = -1
        composeRule.setContent {
            ChordTable(
                rows = rows,
                currentRowIndex = 0,
                selectedRowIndex = null,
                chordsHidden = false,
                density = TableDensity.COMPACT,
                listState = rememberLazyListState(),
                onRowSelected = { selected = it },
                onRowDoubleTapped = {},
            )
        }

        composeRule.onNode(hasContentDescription("Bar 3", substring = true)).performClick()

        assertEquals(2, selected)
    }

    @Test
    fun hidingChordsHidesThemFromTheScreenReaderToo() {
        composeRule.setContent {
            ChordTable(
                rows = rows,
                currentRowIndex = 0,
                selectedRowIndex = null,
                chordsHidden = true,
                density = TableDensity.COMPACT,
                listState = rememberLazyListState(),
                onRowSelected = {},
                onRowDoubleTapped = {},
            )
        }

        // A practice mode that leaks the answer to TalkBack is not a practice mode.
        composeRule.onNode(hasContentDescription("chord hidden", substring = true)).assertIsDisplayed()
        composeRule.onNodeWithText("Cmaj7").assertDoesNotExist()
    }
}
