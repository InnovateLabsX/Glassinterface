package com.glassinterface.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.glassinterface.feature.settings.SettingsScreen

/**
 * Top-level navigation host for GlassInterface.
 *
 * Routes:
 * - "main" → Camera + detection screen
 * - "settings" → User preferences
 */
@Composable
fun GlassNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
