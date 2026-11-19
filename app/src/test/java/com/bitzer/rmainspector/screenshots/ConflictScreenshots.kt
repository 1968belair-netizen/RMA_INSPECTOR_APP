package com.bitzer.rmainspector.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.ConflictReview
import com.bitzer.rmainspector.data.Pick
import com.bitzer.rmainspector.data.ReportDraft
import com.bitzer.rmainspector.data.Summary
import com.bitzer.rmainspector.data.SummaryBlock
import com.bitzer.rmainspector.data.PartRow
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme
import com.bitzer.rmainspector.ui.workspace.ConflictCallbacks
import com.bitzer.rmainspector.ui.workspace.ConflictContent
import com.bitzer.rmainspector.ui.workspace.ConflictUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests of the conflict screen (B4b, spec §6.5): changes on both sides, a summary field changed on both
 * sides (pick needed / picked) and RMA-team fields as information only. FAKE data.
 */
abstract class ConflictCatalog(private val device: String) {
    @get:Rule val compose = createComposeRule()

    private fun c(code: String, desc: String = code) = Code(null, code, desc)

    private val base = ScreenshotData.rma.copy(summary = Summary(blocks = listOf(SummaryBlock("1", "")), inspectedBy = "DI", summaryBy = "DI"))
    private val myReport = ReportDraft(rmaId = "912345", defectCode = c("10", "Lack of lubrication"),
        summaries = mapOf("1" to "The primary failure mode is the scoring of the piston skirts."),
        inspectedBy = "DI", summaryBy = "DI", fieldsDirty = true, rmaSignature = base.signature)

    /** The desk changed status, a part row, the defect code, the summary and the credit status. */
    private val deskEdited = base.copy(
        signature = "sig-desk",
        header = base.header.copy(inspectionStatus = c("Inspected"), creditStatus = "F - Full Warranty Credit"),
        parts = listOf(PartRow("L1.y", c("07a", "Terminal box"), c("50", "Normal")),
            PartRow("L2.y", c("48a", "Winding resistance"), c("50", "Normal"), note = "Within spec, all 3 phases"),
            PartRow("L3.y", c("44", "Stator"), c("46", "Burnt"))),
        summary = Summary(exists = true, defectCode = c("07", "Terminal failure"),
            blocks = listOf(SummaryBlock("1", "Terminal plate cracked; typed at the desk.")), inspectedBy = "DI", summaryBy = "DI"),
    )
    private val teamOnly = base.copy(signature = "sig-desk", header = base.header.copy(creditStatus = "FD - Freight Damage"))

    private fun review(current: com.bitzer.rmainspector.data.RmaDetail, report: ReportDraft?) =
        ConflictReview.build(base, current, ScreenshotData.findingRows, report, photosPending = 3,
            blockLabels = mapOf("1" to "Report summary"))

    private fun shot(name: String, ui: ConflictUiState) {
        compose.setContent { RmaInspectorTheme { ConflictContent(ui, ConflictCallbacks()) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    @Test fun conflict_screen() = shot("conflict_screen", ConflictUiState(review(deskEdited, null)))
    @Test fun conflict_both_sides_summary() = shot("conflict_both_sides_summary", ConflictUiState(review(deskEdited, myReport)))
    @Test fun conflict_both_sides_picked() = shot("conflict_both_sides_picked",
        ConflictUiState(review(deskEdited, myReport), picks = mapOf("block:1" to Pick.COMBINE, "defect" to Pick.MINE)))
    @Test fun conflict_team_field_info() = shot("conflict_team_field_info", ConflictUiState(review(teamOnly, null)))
    @Test fun conflict_discard_confirm() = shot("conflict_discard_confirm", ConflictUiState(review(deskEdited, null), confirmDiscard = true))
}

/** 11" tablet, landscape (target device). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ConflictTabletScreenshots : ConflictCatalog("tablet")

/** Phone, portrait. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class ConflictPhoneScreenshots : ConflictCatalog("phone")
