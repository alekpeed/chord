package com.alekpeed.hearsay.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.alekpeed.hearsay.feature.capture.ui.CaptureRoute
import com.alekpeed.hearsay.feature.eartraining.ui.EarTrainingRoute
import com.alekpeed.hearsay.feature.library.ui.LibraryRoute
import com.alekpeed.hearsay.feature.performance.PerformanceViewModel
import com.alekpeed.hearsay.feature.performance.ui.PerformanceRoute
import com.alekpeed.hearsay.feature.processing.ui.ProcessingRoute
import com.alekpeed.hearsay.ui.screens.SettingsPlaceholderScreen

/** The destinations reachable from the navigation rail or bar. */
enum class TopLevelRoute(val route: String, val label: String) {
    LIBRARY("library", "Library"),
    EAR_TRAINING("ear-training", "Ear training"),
    PROCESSING("processing", "Processing"),
    CAPTURE("capture", "Record chords"),
    SETTINGS("settings", "Settings"),
}

object Destinations {
    const val Performance = "project/{${PerformanceViewModel.ProjectIdKey}}"

    fun performance(projectId: String): String = "project/$projectId"
}

@Composable
fun HearsayNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelRoute.LIBRARY.route,
        modifier = modifier,
    ) {
        composable(TopLevelRoute.LIBRARY.route) {
            LibraryRoute(
                onOpenProject = { projectId -> navController.navigate(Destinations.performance(projectId)) },
            )
        }

        composable(
            route = Destinations.Performance,
            arguments = listOf(
                navArgument(PerformanceViewModel.ProjectIdKey) { type = NavType.StringType },
            ),
        ) {
            PerformanceRoute(onBack = { navController.popBackStack() })
        }

        composable(TopLevelRoute.EAR_TRAINING.route) {
            EarTrainingRoute(
                onOpenProject = { projectId -> navController.navigate(Destinations.performance(projectId)) },
            )
        }
        composable(TopLevelRoute.PROCESSING.route) {
            ProcessingRoute(
                onOpenProject = { projectId -> navController.navigate(Destinations.performance(projectId)) },
            )
        }
        composable(TopLevelRoute.CAPTURE.route) { CaptureRoute() }
        composable(TopLevelRoute.SETTINGS.route) { SettingsPlaceholderScreen() }
    }
}
