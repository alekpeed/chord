package com.alekpeed.hearsay.feature.performance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alekpeed.hearsay.core.model.timeline.ChartRow
import kotlin.math.roundToInt

@Composable
fun ChordTable(
    rows: List<ChartRow>,
    currentRowIndex: Int,
    selectedRowIndex: Int?,
    chordsHidden: Boolean,
    density: TableDensity,
    listState: LazyListState,
    onRowSelected: (Int) -> Unit,
    onRowDoubleTapped: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "header") { TableHeader(density) }

        items(rows, key = { it.eventId }) { row ->
            ChordTableRow(
                row = row,
                isCurrent = row.index == currentRowIndex,
                isSelected = row.index == selectedRowIndex,
                chordsHidden = chordsHidden,
                density = density,
                onSelected = { onRowSelected(row.index) },
                onPlayFrom = { onRowDoubleTapped(row.index) },
            )
        }
    }
}

@Composable
private fun TableHeader(density: TableDensity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Bar", MeasureColumnWidth)
        if (density == TableDensity.EXPANDED) HeaderCell("Section", SectionColumnWidth)
        HeaderCell("Chord", ChordColumnWidth)
        HeaderCell("Bass", BassColumnWidth)
        if (density == TableDensity.EXPANDED) HeaderCell("Notes", NotesColumnWidth)
        HeaderCell("Confidence", ConfidenceColumnWidth)
    }
}

@Composable
private fun HeaderCell(label: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun ChordTableRow(
    row: ChartRow,
    isCurrent: Boolean,
    isSelected: Boolean,
    chordsHidden: Boolean,
    density: TableDensity,
    onSelected: () -> Unit,
    onPlayFrom: () -> Unit,
) {
    val background = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val contentColor = when {
        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .selectable(selected = isSelected, onClick = onSelected)
            // A row is a whole statement about one bar; reading its cells out one by one is worse.
            .clearAndSetSemantics { contentDescription = row.describe(chordsHidden) },
    ) {
        if (row.isSectionStart && row.sectionLabel != null && density == TableDensity.COMPACT) {
            Text(
                text = row.sectionLabel.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = RowMinHeight)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Bar zero is the pickup: music before the first downbeat, which is normal.
                text = row.measureNumber?.let { if (it <= 0) "–" else it.toString() } ?: "–",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(MeasureColumnWidth),
            )

            if (density == TableDensity.EXPANDED) {
                Text(
                    text = if (row.isSectionStart) row.sectionLabel.orEmpty() else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(SectionColumnWidth),
                )
            }

            Text(
                text = if (chordsHidden) "•••" else row.displaySymbol,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                modifier = Modifier
                    .width(ChordColumnWidth)
                    .selectable(selected = isSelected, onClick = onPlayFrom),
            )

            Text(
                text = row.bassLabel.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(BassColumnWidth),
            )

            if (density == TableDensity.EXPANDED) {
                Text(
                    text = row.chord?.pitchClasses()?.size?.let { "$it notes" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(NotesColumnWidth),
                )
            }

            Box(modifier = Modifier.width(ConfidenceColumnWidth)) {
                ConfidenceBadge(confidence = row.confidence, userConfirmed = row.userConfirmed)
            }
        }
    }
}

/**
 * Turns a row into one spoken sentence, and keeps hidden chords hidden from the screen reader too —
 * a practice mode that leaks the answer to TalkBack is not a practice mode.
 */
private fun ChartRow.describe(chordsHidden: Boolean): String {
    val bar = measureNumber.let { number ->
        when {
            number == null -> "Unbarred region"
            number <= 0 -> "Pickup, before bar one"
            else -> "Bar $number"
        }
    }
    val chordText = if (chordsHidden) "chord hidden" else displaySymbol
    val bass = bassLabel?.let { ", bass $it" }.orEmpty()
    val certainty = if (userConfirmed) ", confirmed by you" else ", ${(confidence * 100).roundToInt()} percent confidence"
    val section = sectionLabel?.takeIf { isSectionStart }?.let { ", start of $it" }.orEmpty()
    return "$bar$section, $chordText$bass$certainty"
}

private val RowMinHeight = 56.dp
private val MeasureColumnWidth = 56.dp
private val SectionColumnWidth = 120.dp
private val ChordColumnWidth = 160.dp
private val BassColumnWidth = 64.dp
private val NotesColumnWidth = 88.dp
private val ConfidenceColumnWidth = 72.dp
