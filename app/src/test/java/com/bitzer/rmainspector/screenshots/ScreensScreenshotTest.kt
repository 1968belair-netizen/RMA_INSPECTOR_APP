package com.bitzer.rmainspector.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.CodePicking
import com.bitzer.rmainspector.ui.components.CodePickerList
import com.bitzer.rmainspector.ui.components.DropdownField
import com.bitzer.rmainspector.ui.components.MultiSelectChipRow
import com.bitzer.rmainspector.ui.home.HomeContent
import com.bitzer.rmainspector.ui.home.HomeState
import com.bitzer.rmainspector.ui.rma.OverviewState
import com.bitzer.rmainspector.ui.rma.RmaOverviewContent
import com.bitzer.rmainspector.ui.settings.SettingsScreen
import com.bitzer.rmainspector.ui.signin.SignInContent
import com.bitzer.rmainspector.ui.signin.SignInState
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

    @Composable
    private fun signIn(state: SignInState) =
        SignInContent(state, onUsernameChange = {}, onPasswordChange = {}, onSignIn = {}, onOpenSettings = {}, onRetryServer = {})

    @Test fun signin_empty() = shot("signin_empty") { signIn(ScreenshotData.signInEmpty) }
    @Test fun signin_error() = shot("signin_error") { signIn(ScreenshotData.signInError) }
    @Test fun signin_signing_in() = shot("signin_signing_in") { signIn(ScreenshotData.signInBusy) }
    @Test fun signin_offline() = shot("signin_offline") { signIn(ScreenshotData.signInOffline) }

    @Test fun home_default() = shot("home_default") { home(ScreenshotData.homeDefault) }
    @Test fun home_search_results() = shot("home_search_results") { home(ScreenshotData.homeSearch) }
    @Test fun home_empty() = shot("home_empty") { home(ScreenshotData.homeEmpty) }
    @Test fun home_offline() = shot("home_offline") { home(ScreenshotData.homeError, ScreenshotData.appOffline) }

    @Test fun rma_overview() = shot("rma_overview") { RmaOverviewContent(OverviewState.Loaded(ScreenshotData.rma), {}, {}) }
    @Test fun rma_locked() = shot("rma_locked") { RmaOverviewContent(OverviewState.Loaded(ScreenshotData.rmaLocked), {}, {}) }
    @Test fun rma_scroll_read_only() = shot("rma_scroll_read_only") { RmaOverviewContent(OverviewState.Loaded(ScreenshotData.rmaScroll), {}, {}) }
    @Test fun rma_not_found() = shot("rma_not_found") { RmaOverviewContent(OverviewState.Error("RMA 999 was not found.", notFound = true), {}, {}) }

    // --- Shared code pickers (A3)
    @Test fun dropdown_closed() = shot("dropdown_closed") {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp)) {
                DropdownField("Part", selected = ScreenshotData.manyParts.first { it.code == "48a" }, onClick = {})
                Spacer(Modifier.height(16.dp))
                DropdownField("Issue", selected = ScreenshotData.issueCodes.first { it.code == "50" }, onClick = {})
                Spacer(Modifier.height(16.dp))
                DropdownField("Cause", selected = null, onClick = {})
            }
        }
    }

    @Composable
    private fun picker(title: String, items: List<com.bitzer.rmainspector.data.Code>, query: String, pinned: List<CodeKey> = emptyList(),
                       recent: List<CodeKey> = emptyList(), selected: CodeKey? = null) {
        val arranged = CodePicking.arrange(items, pinned)
        val filtered = CodePicking.filter(arranged, query)
        Surface(Modifier.fillMaxSize()) {
            CodePickerList(title, query, {}, CodePicking.rows(filtered, pinned, withHeaders = query.isBlank()),
                CodePicking.recent(arranged, recent), selected, {}, onClose = {})
        }
    }

    @Test fun dropdown_open_issues() = shot("dropdown_open_issues") {
        picker("Issue", ScreenshotData.issueCodes, "", ScreenshotData.normalIssues, selected = CodeKey(null, "50"))
    }
    @Test fun dropdown_open_long() = shot("dropdown_open_long") {
        picker("Part", ScreenshotData.manyParts, "", recent = ScreenshotData.recentParts, selected = CodeKey(null, "48a"))
    }
    @Test fun dropdown_filtered() = shot("dropdown_filtered") { picker("Part", ScreenshotData.manyParts, "48") }
    @Test fun dropdown_no_match() = shot("dropdown_no_match") { picker("Part", ScreenshotData.manyParts, "zzz") }

    @Test fun chip_row() = shot("chip_row") {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp)) {
                MultiSelectChipRow(ScreenshotData.productTypeChips, setOf(CodeKey(null, "Recip"), CodeKey(null, "Screw")), {},
                    label = "Product type")
                Spacer(Modifier.height(16.dp))
                MultiSelectChipRow(ScreenshotData.returnCodeChips, setOf(CodeKey(null, "Warranty")), {}, label = "Return code")
            }
        }
    }

    @Test fun settings() = shot("settings") {
        SettingsScreen(ScreenshotData.appOk, onBack = {}, onSaveServer = { null }, onResetServer = {}, onTest = {}, onSignOut = {})
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
