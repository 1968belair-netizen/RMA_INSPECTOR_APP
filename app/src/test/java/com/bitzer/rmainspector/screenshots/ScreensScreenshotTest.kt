package com.bitzer.rmainspector.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bitzer.rmainspector.ui.home.HomeContent
import com.bitzer.rmainspector.ui.home.HomeState
import com.bitzer.rmainspector.ui.rma.OverviewState
import com.bitzer.rmainspector.ui.rma.RmaOverviewContent
import com.bitzer.rmainspector.ui.settings.SettingsScreen
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests (Roborazzi). One PNG per screen state, per device size. CI records them with
 * `./gradlew recordRoborazziDebug` and posts changed ones on the PR. To add a screen: add a
 * `@Test fun x() = shot("name") { ... }` in [ScreenCatalog] — both device classes pick it up.
 */
abstract class ScreenCatalog(private val device: String) {
    @get:Rule val compose = createComposeRule()

    protected fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { RmaInspectorTheme { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    @Composable
    private fun home(state: HomeState, app: com.bitzer.rmainspector.ui.AppUiState = ScreenshotData.appOk) =
        HomeContent(app, state, query = if (state.search is com.bitzer.rmainspector.ui.home.SearchState.Results) "25X" else "",
            onQueryChange = {}, onSearch = {}, onClearSearch = {}, onToggleStatus = {}, onRefreshWorkList = {},
            onOpenSettings = {}, onRetryServer = {}, onOpenRma = {})

    @Test fun home_default() = shot("home_default") { home(ScreenshotData.homeDefault) }
    @Test fun home_search_results() = shot("home_search_results") { home(ScreenshotData.homeSearch) }
    @Test fun home_empty() = shot("home_empty") { home(ScreenshotData.homeEmpty) }
    @Test fun home_offline() = shot("home_offline") { home(ScreenshotData.homeError, ScreenshotData.appOffline) }

    @Test fun rma_overview() = shot("rma_overview") { RmaOverviewContent(OverviewState.Loaded(ScreenshotData.rma), {}, {}) }
    @Test fun rma_locked() = shot("rma_locked") { RmaOverviewContent(OverviewState.Loaded(ScreenshotData.rmaLocked), {}, {}) }
    @Test fun rma_scroll_read_only() = shot("rma_scroll_read_only") { RmaOverviewContent(OverviewState.Loaded(ScreenshotData.rmaScroll), {}, {}) }
    @Test fun rma_not_found() = shot("rma_not_found") { RmaOverviewContent(OverviewState.Error("RMA 999 was not found.", notFound = true), {}, {}) }

    @Test fun settings() = shot("settings") {
        SettingsScreen(ScreenshotData.appOk, onBack = {}, onSaveServer = { null }, onResetServer = {}, onTest = {}, onSelectInspector = {})
    }
}

/** 11" tablet, landscape (target device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletScreenshots : ScreenCatalog("tablet")

/** Phone, portrait (progress previews). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class PhoneScreenshots : ScreenCatalog("phone")
