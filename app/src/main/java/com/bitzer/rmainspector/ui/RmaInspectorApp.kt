package com.bitzer.rmainspector.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bitzer.rmainspector.ui.home.HomeScreen
import com.bitzer.rmainspector.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/** App navigation. Screens from docs/spec.md §3 are added here feature by feature (Phase 3). */
@Composable
fun RmaInspectorApp() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(onOpenSettings = { nav.navigate(Routes.SETTINGS) }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
