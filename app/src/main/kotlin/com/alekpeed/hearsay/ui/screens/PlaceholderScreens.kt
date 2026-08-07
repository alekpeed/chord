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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.alekpeed.hearsay.BuildConfig

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
    version: String? = null,
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
        if (version != null) {
            Text(
                text = version,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 520.dp).testTag(VersionTestTag),
            )
        }
    }
}

/** Named so a screenshot of the version can be located, and so a test can assert it is shown. */
const val VersionTestTag = "app-version"

@Composable
fun SettingsPlaceholderScreen(modifier: Modifier = Modifier) = NotBuiltYetScreen(
    title = "Settings",
    explanation = "Processing profile, notation preferences, model downloads and storage management " +
        "will live here.",
    blockedBy = "Not built yet. Nothing in this build sends data anywhere: everything is stored on this device.",
    // The commit this build came from. Without it, a report of what the app did cannot be tied to a
    // version, and a fix that is working is indistinguishable from one that was never installed.
    version = "Hearsay ${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.GIT_SHA}",
    modifier = modifier,
)
