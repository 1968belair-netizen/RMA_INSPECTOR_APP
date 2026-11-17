package com.bitzer.rmainspector.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bitzer.rmainspector.ui.components.InspectorPickDialog
import com.bitzer.rmainspector.ui.home.HomeScreen
import com.bitzer.rmainspector.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
}

/** App navigation. Screens from docs/spec.md §3 are added here feature by feature (Phase 3). */
@Composable
fun RmaInspectorApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(state = state, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onRetry = vm::refresh)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                state = state,
                onBack = { nav.popBackStack() },
                onSaveServer = vm::saveServerUrl,
                onResetServer = vm::resetServerUrl,
                onTest = vm::refresh,
                onSelectInspector = vm::selectInspector,
            )
        }
    }

    if (state.needsInspectorPick) {
        InspectorPickDialog(state.inspectors, onSelect = vm::selectInspector)
    }
}
