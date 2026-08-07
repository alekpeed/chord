package com.alekpeed.hearsay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Destinations that exist in the navigation model but have no feature behind them yet.
 *
 * They say plainly what is missing and what has to come first, rather than showing an empty list
 * that reads like a bug or a feature that quietly does nothing.
 */
@Composable
private fun NotBuiltYetScreen(
    title: String,
    explanation: String,
    blockedBy: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Text(
            text = blockedBy,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 520.dp),
        )
    }
}

@Composable
fun SettingsPlaceholderScreen(modifier: Modifier = Modifier) = NotBuiltYetScreen(
    title = "Settings",
    explanation = "Processing profile, notation preferences, model downloads and storage management " +
        "will live here.",
    blockedBy = "Not built yet. Nothing in this build sends data anywhere: everything is stored on this device.",
    modifier = modifier,
)
