package com.alekpeed.hearsay.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alekpeed.hearsay.navigation.HearsayNavHost
import com.alekpeed.hearsay.navigation.TopLevelRoute

/**
 * The navigation shell.
 *
 * [NavigationSuiteScaffold] picks a rail on a tablet and a bottom bar on a phone, which is the
 * difference the product cares about: on a music stand the chord table must not lose height to
 * navigation chrome.
 */
@Composable
fun HearsayApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            TopLevelRoute.entries.forEach { route ->
                val selected = currentDestination?.hierarchy?.any { it.route == route.route } == true
                item(
                    selected = selected,
                    onClick = {
                        navController.navigate(route.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(route.icon, contentDescription = null) },
                    label = { Text(route.label) },
                )
            }
        },
    ) {
        HearsayNavHost(navController = navController)
    }
}

internal val TopLevelRoute.icon: ImageVector
    get() = when (this) {
        TopLevelRoute.LIBRARY -> Icons.Filled.LibraryMusic
        TopLevelRoute.EAR_TRAINING -> Icons.Filled.Hearing
        TopLevelRoute.PROCESSING -> Icons.Filled.GraphicEq
        TopLevelRoute.CAPTURE -> Icons.Filled.Piano
        TopLevelRoute.SETTINGS -> Icons.Filled.Settings
    }
