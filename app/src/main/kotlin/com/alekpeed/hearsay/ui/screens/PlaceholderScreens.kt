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
fun EarTrainingPlaceholderScreen(modifier: Modifier = Modifier) = NotBuiltYetScreen(
    title = "Ear training",
    explanation = "Exercises are generated from your own analyzed songs — the actual harmony, voicings and " +
        "bass movement in music you are studying.",
    blockedBy = "Waiting on analysis: a question can only be asked about a chord the app is confident in, " +
        "or one you have confirmed yourself.",
    modifier = modifier,
)

@Composable
fun ProcessingPlaceholderScreen(modifier: Modifier = Modifier) = NotBuiltYetScreen(
    title = "Processing queue",
    explanation = "Stem separation, beat tracking and chord recognition will report their progress here, " +
        "stage by stage, and can be paused or cancelled.",
    blockedBy = "Not built yet. The playback, project and correction layers come first, so that analysis " +
        "output has somewhere trustworthy to land.",
    modifier = modifier,
)

@Composable
fun SettingsPlaceholderScreen(modifier: Modifier = Modifier) = NotBuiltYetScreen(
    title = "Settings",
    explanation = "Processing profile, notation preferences, model downloads and storage management " +
        "will live here.",
    blockedBy = "Not built yet. Nothing in this build sends data anywhere: everything is stored on this device.",
    modifier = modifier,
)
