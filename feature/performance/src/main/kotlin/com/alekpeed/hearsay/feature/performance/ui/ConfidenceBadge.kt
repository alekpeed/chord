package com.alekpeed.hearsay.feature.performance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * How sure the app is about a chord.
 *
 * Confidence is drawn as a filled proportion rather than only a number, so a table can be scanned
 * for weak spots at arm's length on a music stand. A chord the user confirmed stops being a
 * probability at all and says so.
 */
@Composable
fun ConfidenceBadge(
    confidence: Float,
    userConfirmed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (userConfirmed) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp),
            )
            Text(
                text = "You",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        return
    }

    val clamped = confidence.coerceIn(0f, 1f)
    val tint = when {
        clamped >= HighConfidence -> MaterialTheme.colorScheme.primary
        clamped >= MediumConfidence -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidthFraction(clamped)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint),
            )
        }
        Text(
            text = "${(clamped * 100).roundToInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun Modifier.fillMaxWidthFraction(fraction: Float): Modifier =
    this.width((28 * fraction.coerceIn(0f, 1f)).dp)

private const val HighConfidence = 0.8f
private const val MediumConfidence = 0.5f
