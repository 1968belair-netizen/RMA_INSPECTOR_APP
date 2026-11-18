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
import com.bitzer.rmainspector.ui.settings.SettingsScreen
import com.bitzer.rmainspector.ui.signin.SignInContent
import com.bitzer.rmainspector.ui.signin.SignInState
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.bitzer.rmainspector.ui.workspace.WorkspaceCallbacks
import com.bitzer.rmainspector.ui.workspace.WorkspaceContent
import com.bitzer.rmainspector.ui.workspace.WorkspaceLoad
import com.bitzer.rmainspector.ui.workspace.WorkspaceState
import com.bitzer.rmainspector.ui.workspace.WorkspaceTab
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
            onQueryChange = {}, onSearch = {}, onClearSearch = {}, onToggleProductType = {}, onToggleReturnCode = {},
            onRefreshWorkList = {}, onOpenSettings = {}, onRetryServer = {}, onOpenRma = {}, today = ScreenshotData.today)

    @Composable
    private fun signIn(state: SignInState) =
        SignInContent(state, onUsernameChange = {}, onPasswordChange = {}, onSignIn = {}, onOpenSettings = {}, onRetryServer = {})

    @Test fun signin_empty() = shot("signin_empty") { signIn(ScreenshotData.signInEmpty) }
    @Test fun signin_error() = shot("signin_error") { signIn(ScreenshotData.signInError) }
    @Test fun signin_signing_in() = shot("signin_signing_in") { signIn(ScreenshotData.signInBusy) }
    @Test fun signin_offline() = shot("signin_offline") { signIn(ScreenshotData.signInOffline) }

    @Test fun home_default() = shot("home_default") { home(ScreenshotData.homeDefault) }
    @Test fun home_search_results() = shot("home_search_results") { home(ScreenshotData.homeSearch) }
    @Test fun home_filtered() = shot("home_filtered") { home(ScreenshotData.homeFiltered) }
    @Test fun home_empty() = shot("home_empty") { home(ScreenshotData.homeEmpty) }
    @Test fun home_error() = shot("home_error") { home(ScreenshotData.homeError) }
    @Test fun home_offline() = shot("home_offline") { home(ScreenshotData.homeOffline, ScreenshotData.appOffline) }

    // --- Inspection workspace (A5): header, banners, tabs, two panes (tablet) / list → detail (phone)
    @Composable
    private fun workspace(state: WorkspaceState) = WorkspaceContent(state, WorkspaceCallbacks())

    @Test fun workspace_findings() = shot("workspace_findings") { workspace(ScreenshotData.workspace) }
    @Test fun workspace_findings_selected() = shot("workspace_findings_selected") {
        workspace(ScreenshotData.workspace.copy(selection = ScreenshotData.rma.parts[1].rowId))
    }
    @Test fun workspace_failure_info_expanded() = shot("workspace_failure_info_expanded") {
        workspace(ScreenshotData.workspace.copy(failureExpanded = true))
    }
    @Test fun workspace_photos() = shot("workspace_photos") { workspace(ScreenshotData.workspace.copy(tab = WorkspaceTab.PHOTOS)) }
    @Test fun workspace_report() = shot("workspace_report") { workspace(ScreenshotData.workspace.copy(tab = WorkspaceTab.REPORT)) }
    @Test fun workspace_locked() = shot("workspace_locked") { workspace(ScreenshotData.workspaceLocked) }
    @Test fun workspace_scroll_read_only() = shot("workspace_scroll_read_only") { workspace(ScreenshotData.workspaceScroll) }
    @Test fun workspace_closed() = shot("workspace_closed") { workspace(ScreenshotData.workspaceClosed) }
    @Test fun workspace_changed_elsewhere() = shot("workspace_changed_elsewhere") {
        workspace(ScreenshotData.workspace.copy(changedElsewhere = true))
    }
    // --- Findings tab (B1): list, part sheet, empty, long note, undo
    @Test fun findings_list() = shot("findings_list") { workspace(ScreenshotData.workspaceFindings) }
    @Test fun findings_sheet() = shot("findings_sheet") { workspace(ScreenshotData.workspaceFindingSheet) }
    @Test fun findings_empty() = shot("findings_empty") { workspace(ScreenshotData.workspaceFindingsEmpty) }
    @Test fun findings_long_note() = shot("findings_long_note") { workspace(ScreenshotData.workspaceFindingLongNote) }
    @Test fun findings_undo() = shot("findings_undo") { workspace(ScreenshotData.workspaceFindingsUndo) }
    @Test fun photos_grid_by_part() = shot("photos_grid_by_part") { workspace(ScreenshotData.workspacePhotosGrid) }
    @Test fun photos_photo_selected() = shot("photos_photo_selected") { workspace(ScreenshotData.workspacePhotoSelected) }
    @Test fun photos_empty() = shot("photos_empty") { workspace(ScreenshotData.workspacePhotosEmpty) }
    @Test fun photos_offline() = shot("photos_offline") { workspace(ScreenshotData.workspacePhotosOffline) }
    @Test fun photos_tag_picker() = shot("photos_tag_picker") { workspace(ScreenshotData.workspaceTagPicker) }
    @Test fun photos_tag_picker_empty() = shot("photos_tag_picker_empty") { workspace(ScreenshotData.workspaceTagPickerEmpty) }
    @Test fun home_draft_changed() = shot("home_draft_changed") { home(ScreenshotData.homeDraftChanged) }

    @Test fun workspace_not_found() = shot("workspace_not_found") {
        workspace(WorkspaceState(load = WorkspaceLoad.Error("RMA 999 was not found.", notFound = true)))
    }

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
