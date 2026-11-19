package com.bitzer.rmainspector.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bitzer.rmainspector.BuildConfig
import com.bitzer.rmainspector.ui.home.HomeScreen
import com.bitzer.rmainspector.ui.workspace.WorkspaceScreen
import com.bitzer.rmainspector.ui.workspace.WorkspaceViewModel
import com.bitzer.rmainspector.ui.settings.SettingsScreen
import com.bitzer.rmainspector.ui.signin.SignInScreen

object Routes {
    const val SIGN_IN = "signin"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val RMA = "rma/{${WorkspaceViewModel.ARG_RMA_ID}}"

    /** rma_id is opaque — encode it so any characters survive the route. */
    fun rma(rmaId: String) = "rma/" + Uri.encode(rmaId)
}

/** App navigation. Sign-in is the first screen until the user signs in (spec §2). */
@Composable
fun RmaInspectorApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val start = remember { if (vm.state.value.signedIn) Routes.HOME else Routes.SIGN_IN }

    // Sign-in / sign-out navigation (spec §2, decision 2026-11-18):
    // - Sign out (Settings) → sign-in with a cleared back stack.
    // - Token rejected mid-session (401) → sign-in on top of the current screen; the screens below are kept.
    // - Signed in → back to the screen the user was on if there is one, otherwise Home.
    LaunchedEffect(state.signedIn) {
        val current = nav.currentDestination?.route ?: return@LaunchedEffect
        if (state.signedIn) {
            if (current != Routes.SIGN_IN) return@LaunchedEffect
            val below = nav.previousBackStackEntry?.destination?.route
            if (below != null && below != Routes.SIGN_IN && nav.popBackStack()) return@LaunchedEffect
            nav.navigate(Routes.HOME) {
                popUpTo(nav.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        } else {
            if (current == Routes.SIGN_IN) return@LaunchedEffect
            if (vm.state.value.sessionExpired) {
                nav.navigate(Routes.SIGN_IN) { launchSingleTop = true }
            } else {
                nav.navigate(Routes.SIGN_IN) {
                    popUpTo(nav.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.SIGN_IN) {
            // Back must not get past sign-in to the screens kept below it: leave the app instead.
            val activity = LocalContext.current as? Activity
            BackHandler(enabled = nav.previousBackStackEntry != null) { activity?.finish() }
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
                onDismissEarlierUploads = vm::dismissEarlierUploads,
                onOpenRma = { id -> nav.navigate(Routes.rma(id)) },
            )
        }
        composable(
            Routes.RMA,
            arguments = listOf(navArgument(WorkspaceViewModel.ARG_RMA_ID) { type = NavType.StringType }),
        ) {
            WorkspaceScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                state = state,
                onBack = { nav.popBackStack() },
                onSaveServer = vm::saveServerUrl,
                onResetServer = vm::resetServerUrl,
                onTest = vm::refresh,
                onSignOut = vm::requestSignOut,
                onSendBeforeSignOut = vm::sendBeforeSignOut,
                onSignOutAnyway = vm::signOutAnyway,
                onDismissSignOutPrompt = vm::dismissSignOutPrompt,
                onRetryQueueItem = vm::retryQueueItem,
                onCancelQueueItem = vm::cancelQueueItem,
            )
        }
    }
}
