package com.bitzer.rmainspector.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bitzer.rmainspector.ui.components.InspectorPickDialog
import com.bitzer.rmainspector.ui.home.HomeScreen
import com.bitzer.rmainspector.ui.rma.RmaOverviewScreen
import com.bitzer.rmainspector.ui.rma.RmaOverviewViewModel
import com.bitzer.rmainspector.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val RMA = "rma/{${RmaOverviewViewModel.ARG_RMA_ID}}"

    /** rma_id is opaque — encode it so any characters survive the route. */
    fun rma(rmaId: String) = "rma/" + Uri.encode(rmaId)
}

/** App navigation. Screens from docs/spec.md §3 are added here feature by feature (Phase 3). */
@Composable
fun RmaInspectorApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                app = state,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onRetryServer = vm::refresh,
                onOpenRma = { id -> nav.navigate(Routes.rma(id)) },
            )
        }
        composable(
            Routes.RMA,
            arguments = listOf(navArgument(RmaOverviewViewModel.ARG_RMA_ID) { type = NavType.StringType }),
        ) {
            RmaOverviewScreen(onBack = { nav.popBackStack() })
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
