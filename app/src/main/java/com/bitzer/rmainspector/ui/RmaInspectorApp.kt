package com.bitzer.rmainspector.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bitzer.rmainspector.BuildConfig
import com.bitzer.rmainspector.ui.home.HomeScreen
import com.bitzer.rmainspector.ui.rma.RmaOverviewScreen
import com.bitzer.rmainspector.ui.rma.RmaOverviewViewModel
import com.bitzer.rmainspector.ui.settings.SettingsScreen
import com.bitzer.rmainspector.ui.signin.SignInScreen

object Routes {
    const val SIGN_IN = "signin"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val RMA = "rma/{${RmaOverviewViewModel.ARG_RMA_ID}}"

    /** rma_id is opaque — encode it so any characters survive the route. */
    fun rma(rmaId: String) = "rma/" + Uri.encode(rmaId)
}

/** App navigation. Sign-in is the first screen until the user signs in (spec §2). */
@Composable
fun RmaInspectorApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val start = remember { if (vm.state.value.signedIn) Routes.HOME else Routes.SIGN_IN }

    // Signing in opens Home; signing out (or a 401 from the server) returns to sign-in and clears the back stack.
    LaunchedEffect(state.signedIn) {
        val current = nav.currentDestination?.route ?: return@LaunchedEffect
        val target = if (state.signedIn) Routes.HOME else Routes.SIGN_IN
        if (state.signedIn && current != Routes.SIGN_IN) return@LaunchedEffect
        if (current == target) return@LaunchedEffect
        nav.navigate(target) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.SIGN_IN) {
            SignInScreen(
                initialUsername = vm.lastUsername,
                progress = state.signIn,
                serverUrl = state.settings?.serverUrl.orEmpty(),
                connection = state.connection,
                appVersion = BuildConfig.VERSION_NAME,
                onSignIn = vm::signIn,
                onEdited = vm::clearSignInError,
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onRetryServer = vm::refresh,
            )
        }
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
                onSignOut = vm::signOut,
            )
        }
    }
}
